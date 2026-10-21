package com.launchcatch.coupon.service;

import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.service.model.CouponIssuanceOutcome;
import com.launchcatch.coupon.service.model.CouponIssuanceRequest;
import com.launchcatch.coupon.service.model.CouponIssuanceResult;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/*
 * 발급 요청을 대기열에 모아 작업자 스레드가 묶음으로 처리하게 한다.
 * 요청 스레드는 DB 커넥션을 잡지 않고 대기열에 요청을 넣은 뒤 자기 결과만 기다린다.
 * 작업자는 한 건을 꺼낸 뒤 그동안 쌓인 요청을 최대 묶음 크기까지 바로 함께 꺼낸다.
 * 묶음이 차기를 기다리지 않으므로 한산할 때는 한 건짜리 묶음이 바로 처리된다.
 * 몰릴 때는 앞 묶음을 처리하는 동안 요청이 쌓여 묶음이 저절로 커진다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CouponIssueBatcher implements SmartLifecycle {

    private final CouponBatchIssuanceService batchService;
    private final CouponSequenceService sequenceService;
    private final Clock clock;
    private final List<Thread> workers = new ArrayList<>();
    // 요청 스레드가 넣고 작업자가 꺼내는 대기열이며 start에서 만든다.
    private BlockingQueue<Pending> queue;
    // 작업자 반복과 새 요청 접수를 함께 멈추는 표시다.
    private volatile boolean running;

    // 작업자 수만큼 DB 커넥션을 동시에 쓰므로 커넥션 풀 크기보다 작게 둔다.
    @Value("${coupon.issue.batch.workers:4}")
    private int workerCount;

    // 한 트랜잭션에서 처리할 최대 요청 수이며 IN 절과 다건 INSERT의 길이도 이 값을 넘지 않는다.
    @Value("${coupon.issue.batch.max-size:500}")
    private int maxSize;

    // 대기열이 이 수만큼 차면 새 요청을 기다리게 하지 않고 바로 ISSUANCE_BUSY로 거절한다.
    @Value("${coupon.issue.batch.queue-capacity:2000}")
    private int queueCapacity;

    // 요청 스레드가 결과를 기다리는 상한이며 넘기면 서버 오류로 응답한다.
    @Value("${coupon.issue.batch.wait-timeout:10s}")
    private Duration waitTimeout;

    /**
     * 발급 요청 한 건을 대기열에 넣고 작업자가 묶음으로 처리한 결과를 기다려 돌려준다.
     * 요청 시각은 대기열에 넣는 순간으로 정해 대기열에서 기다린 시간이 발급 시간 판정에 들어가지 않게 한다.
     * 대기열이 가득 찼거나 작업자가 멈춰 있으면 기다리지 않고 바로 ISSUANCE_BUSY로 거절한다.
     * 업무 거절은 요청별 결과에서 CouponException으로 바꿔 던진다.
     * 예상하지 못한 오류는 원래 예외를 그대로 던진다.
     *
     * @param memberId 인증된 회원의 ID
     * @param eventId 발급받을 당일 쿠폰 이벤트의 ID
     * @return 커밋된 발급 응답과 Redis 예약 완료로 확인한 소진 여부
     * @throws CouponException 업무 조건으로 거절됐거나 대기열이 가득 찬 경우
     * @throws IllegalStateException 결과를 기다리는 동안 시간이 다 됐거나 스레드가 중단된 경우
     */
    public CouponIssuanceResult submitAndAwait(long memberId, long eventId) {
        return submitAndAwait(new CouponIssuanceRequest(memberId, eventId,
                LocalDateTime.now(clock.withZone(ClockConfig.ZONE))), System.nanoTime() + waitTimeout.toNanos());
    }

    // 복구 전의 요청 시각과 처리 기한을 유지해 재접수한 요청도 남은 시간만 기다린다.
    public CouponIssuanceResult submitAndAwait(CouponIssuanceRequest request, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new IllegalStateException("Coupon issuance deadline expired");
        }
        Pending pending = new Pending(request, new CompletableFuture<>());
        if (!running || !queue.offer(pending)) {
            throw new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        }
        try {
            return pending.future().get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Coupon batch issuance failed", error.getCause());
        } catch (TimeoutException error) {
            throw new IllegalStateException("Coupon batch issuance did not finish in " + waitTimeout, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for coupon batch issuance", error);
        }
    }

    /*
     * 작업자 스레드의 반복이다.
     * 첫 요청이 올 때까지 최대 200ms씩 기다리며 그 사이 멈춤 표시를 확인한다.
     * 첫 요청을 받으면 이미 쌓인 요청을 묶음 크기까지 함께 꺼내 한 번에 처리한다.
     * 처리 중 어떤 오류가 나도 묶음의 요청이 결과 없이 남지 않게 모두 실패로 끝내고 다음 묶음으로 넘어간다.
     */
    private void runWorker() {
        while (running) {
            Pending first;
            try {
                first = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (first == null) {
                continue;
            }
            List<Pending> batch = new ArrayList<>(maxSize);
            batch.add(first);
            queue.drainTo(batch, maxSize - 1);
            try {
                process(batch);
            } catch (Throwable error) {
                batch.forEach(pending -> pending.future().completeExceptionally(error));
                log.error("event=COUPON_BATCH_WORKER_FAILED size={}", batch.size(), error);
            }
        }
    }

    /*
     * 묶음 하나를 트랜잭션 하나로 처리하고 요청마다 결과를 돌려준다.
     * 업무 거절은 issueBatch가 요청별 결과로 돌려주므로 여기까지 오지 않는다.
     * 예외로 전파되는 오류는 모든 요청에 전달하며 묶음을 나누거나 자동으로 재시도하지 않는다.
     */
    private void process(List<Pending> batch) {
        List<CouponIssuanceOutcome> outcomes;
        try {
            outcomes = batchService.issueBatch(batch.stream().map(Pending::request).toList());
        } catch (RuntimeException error) {
            batch.forEach(pending -> pending.future().completeExceptionally(error));
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            CouponIssuanceOutcome outcome = outcomes.get(i);
            if (outcome.rejection() != null) {
                CouponException rejection = new CouponException(outcome.rejection());
                if (outcome.recoveryRequired()) {
                    rejection = CouponException.recoveryRequired(batch.get(i).request().eventId());
                    rejection.confirmIssuanceNotCommitted();
                }
                batch.get(i).future().completeExceptionally(rejection);
            } else {
                // 요청 스레드가 타임아웃됐어도 커밋된 예약은 작업자가 완료한다.
                boolean soldOut = sequenceService.complete(outcome.reservation());
                batch.get(i).future().complete(new CouponIssuanceResult(outcome.response(), soldOut));
            }
        }
    }

    // 스프링이 시작할 때 대기열을 만들고 작업자 스레드를 띄운다.
    @Override
    public void start() {
        queue = new ArrayBlockingQueue<>(queueCapacity);
        running = true;
        for (int i = 0; i < workerCount; i++) {
            workers.add(Thread.ofPlatform().name("coupon-batch-" + i).daemon(true).start(this::runWorker));
        }
        log.info("event=COUPON_BATCH_STARTED workers={} maxSize={} queueCapacity={}", workerCount, maxSize, queueCapacity);
    }

    /*
     * 스프링이 종료할 때 새 요청 접수와 작업자 반복을 멈춘다.
     * 작업자가 처리 중인 묶음은 최대 5초 기다려 끝낸다.
     * 대기열에 남은 요청은 ISSUANCE_BUSY로 끝내 요청 스레드가 결과를 무한히 기다리지 않게 한다.
     */
    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            try {
                worker.join(5000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        if (queue != null) {
            List<Pending> left = new ArrayList<>();
            queue.drainTo(left);
            left.forEach(pending -> pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY)));
        }
    }

    // 작업자가 요청을 받고 있으면 true를 돌려주며 스프링이 종료 순서를 정할 때 쓴다.
    @Override
    public boolean isRunning() {
        return running;
    }

    // 대기열에 들어간 요청 한 건과 그 결과를 요청 스레드에 넘겨줄 통로다.
    private record Pending(CouponIssuanceRequest request,
            CompletableFuture<CouponIssuanceResult> future) { }
}
