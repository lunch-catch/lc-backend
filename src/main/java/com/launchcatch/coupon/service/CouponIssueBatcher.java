package com.launchcatch.coupon.service;

import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.model.CouponIssuanceOutcome;
import com.launchcatch.coupon.model.CouponIssuanceRequest;
import com.launchcatch.coupon.model.CouponIssuanceResult;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import com.launchcatch.coupon.service.model.CouponBatchDeadline;
import java.util.concurrent.ScheduledExecutorService;
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
 * 최대 20ms만 추가 요청을 모으며 요청 기한이나 묶음 상한에 닿으면 먼저 처리한다.
 * 몰릴 때는 앞 묶음을 처리하는 동안 요청이 쌓여 묶음이 저절로 커진다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CouponIssueBatcher implements SmartLifecycle {

    private static final long MAX_COLLECTION_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private final CouponBatchIssuanceService batchService;
    private final CouponSequenceService sequenceService;
    private final CouponSoldOutNotificationService soldOutNotifications;
    private final Clock clock;
    private final List<Thread> workers = new ArrayList<>();
    // 요청 스레드가 넣고 작업자가 꺼내는 대기열이며 start에서 만든다.
    private BlockingQueue<Pending> queue;
    private BlockingQueue<Pending> awaitingReservations;
    private ScheduledExecutorService reservationWaiter;
    private ExecutorService eventTasks;
    private Semaphore admission;
    private final Set<Pending> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // 접수 상한 안에서 이벤트마다 한 번씩 예약 상태를 조회한다.
    private final Set<Long> pollingEvents = java.util.concurrent.ConcurrentHashMap.newKeySet();
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

    // 커밋 전까지 결과를 기다리는 기한이며 만료되면 취소를 요청한다.
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
     * @throws CouponException 처리 기한이 끝나 커밋 전에 취소한 경우
     */
    public CouponIssuanceResult submitAndAwait(long memberId, long eventId) {
        return submitAndAwait(new CouponIssuanceRequest(memberId, eventId,
                LocalDateTime.now(clock.withZone(ClockConfig.ZONE))), System.nanoTime() + waitTimeout.toNanos());
    }

    // 복구 전의 요청 시각과 처리 기한을 유지해 재접수한 요청도 남은 시간만 기다린다.
    public CouponIssuanceResult submitAndAwait(CouponIssuanceRequest request, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        }
        if (!running || !admission.tryAcquire()) {
            throw new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        }
        Pending pending = new Pending(request, new CompletableFuture<>(), deadlineNanos);
        active.add(pending);
        pending.future().whenComplete((result, error) -> {
            active.remove(pending);
            admission.release();
        });
        if (!running || !queue.offer(pending)) {
            pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY));
            throw new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        }
        if (!running && queue.remove(pending)) {
            pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY));
        }
        try {
            return pending.future().get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("Coupon batch issuance failed", error.getCause());
        } catch (TimeoutException error) {
            return cancelOrAwaitCommit(pending);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return cancelOrAwaitCommit(pending);
        }
    }

    private CouponIssuanceResult cancelOrAwaitCommit(Pending pending) {
        if (pending.cancel()) {
            queue.remove(pending);
            awaitingReservations.remove(pending);
            // 실행 중인 묶음은 롤백 후 Future를 끝내므로 접수 허용량도 그때 반납한다.
            if (pending.deadline() == null) {
                pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY));
            }
            throw new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        }
        try {
            return pending.future().join();
        } catch (java.util.concurrent.CompletionException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw error;
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
            try {
                collectMoreRequests(first, batch);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                batch.forEach(pending -> pending.future().completeExceptionally(
                        new CouponException(CouponErrorCode.ISSUANCE_BUSY)));
                return;
            }
            batch.removeIf(this::expireBeforeStart);
            if (batch.isEmpty()) {
                continue;
            }
            CouponLogContext context = CouponLogContext.shared(batch.stream().map(Pending::logContext).toList());
            try (var ignored = context.open()) {
                try {
                    process(batch);
                } catch (Throwable error) {
                    batch.forEach(pending -> pending.future().completeExceptionally(error));
                    CouponFailureDetails details = CouponFailureDetails.from(error);
                    log.error("event=COUPON_BATCH_WORKER_FAILED requestCount={} requestTraceIds={} exceptionType={} httpStatus={} sqlState={} sqlErrorCode={} origin=\"{}\"",
                            batch.size(), CouponLogContext.requestTraceIds(), details.exceptionType(), details.httpStatus(),
                            details.sqlState(), details.sqlErrorCode(), details.origin());
                }
            }
        }
    }

    private void collectMoreRequests(Pending first, List<Pending> batch) throws InterruptedException {
        queue.drainTo(batch, maxSize - 1);
        long collectUntil = Math.min(first.deadlineNanos(), System.nanoTime() + MAX_COLLECTION_NANOS);
        while (batch.size() < maxSize) {
            long remaining = collectUntil - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            Pending next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batch.add(next);
            queue.drainTo(batch, maxSize - batch.size());
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
            CouponBatchDeadline deadline = new CouponBatchDeadline(batch.stream()
                    .mapToLong(Pending::deadlineNanos).min().orElseThrow());
            batch.forEach(pending -> pending.attach(deadline));
            deadline.check();
            outcomes = batchService.issueBatch(batch.stream().map(Pending::request).toList(), deadline);
        } catch (RuntimeException error) {
            if (!(error instanceof CouponException)) {
                CouponFailureDetails details = CouponFailureDetails.from(error);
                log.error("event=COUPON_BATCH_ISSUANCE_FAILED requestCount={} requestTraceIds={} exceptionType={} httpStatus={} sqlState={} sqlErrorCode={} origin=\"{}\"",
                        batch.size(), CouponLogContext.requestTraceIds(), details.exceptionType(), details.httpStatus(),
                        details.sqlState(), details.sqlErrorCode(), details.origin());
            }
            batch.forEach(pending -> pending.future().completeExceptionally(error));
            return;
        }
        Map<Long, List<Completion>> byEvent = new LinkedHashMap<>();
        for (int i = 0; i < batch.size(); i++) {
            Pending pending = batch.get(i);
            byEvent.computeIfAbsent(pending.request().eventId(), key -> new ArrayList<>())
                    .add(new Completion(pending, outcomes.get(i)));
        }
        byEvent.values().forEach(completions -> dispatchEvent(CouponLogContext.shared(completions.stream()
                .map(completion -> completion.pending().logContext()).toList()), () -> finishEvent(completions)));
    }

    private void dispatchEvent(CouponLogContext context, Runnable task) {
        Runnable contextualTask = () -> {
            try (var ignored = context.open()) {
                task.run();
            }
        };
        try {
            eventTasks.execute(contextualTask);
        } catch (java.util.concurrent.RejectedExecutionException error) {
            // 종료와 경쟁해도 커밋된 예약의 정리와 결과 전달은 생략하지 않는다.
            contextualTask.run();
        }
    }

    // 커밋된 결과의 Redis 완료와 응답을 이벤트별로 처리해 다른 이벤트의 지연을 기다리지 않는다.
    private void finishEvent(List<Completion> completions) {
        try {
            Set<Long> soldOutEvents = sequenceService.completeAll(completions.stream().map(Completion::outcome)
                    .filter(outcome -> outcome.reservation() != null).map(CouponIssuanceOutcome::reservation).toList());
            soldOutEvents.forEach(soldOutNotifications::request);
            for (Completion completion : completions) {
                try (var ignored = completion.pending().logContext().open()) {
                    completeOrDeferRequest(completion, soldOutEvents);
                }
            }
        } catch (Throwable error) {
            completions.forEach(completion -> completion.pending().future().completeExceptionally(error));
            CouponFailureDetails details = CouponFailureDetails.from(error);
            log.error("event=COUPON_BATCH_COMPLETION_FAILED requestCount={} requestTraceIds={} exceptionType={} httpStatus={} sqlState={} sqlErrorCode={} origin=\"{}\"",
                    completions.size(), CouponLogContext.requestTraceIds(), details.exceptionType(), details.httpStatus(),
                    details.sqlState(), details.sqlErrorCode(), details.origin());
        }
    }

    private void completeOrDeferRequest(Completion completion, Set<Long> soldOutEvents) {
        CouponIssuanceOutcome outcome = completion.outcome();
        Pending pending = completion.pending();
        if (outcome.awaitingReservation()) {
            pending.detach();
            deferReservation(pending);
        } else if (outcome.rejection() != null) {
            CouponException rejection = new CouponException(outcome.rejection());
            if (outcome.recoveryRequired()) {
                rejection = CouponException.recoveryRequired(pending.request().eventId());
                rejection.confirmIssuanceNotCommitted();
            }
            pending.future().completeExceptionally(rejection);
        } else {
            pending.future().complete(new CouponIssuanceResult(outcome.response(),
                    soldOutEvents.contains(outcome.reservation().eventId())));
        }
    }

    // DB 작업을 시작하기 전에 취소되거나 기한이 지난 요청을 제외한다.
    private boolean expireBeforeStart(Pending pending) {
        if (!running || pending.future().isDone() || pending.cancelled()) {
            pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY));
            return true;
        }
        if (pending.deadlineNanos() - System.nanoTime() <= 0) {
            pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY));
            return true;
        }
        return false;
    }

    private void deferReservation(Pending pending) {
        if (!expireBeforeStart(pending) && (!running || !awaitingReservations.offer(pending))) {
            pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY));
        }
    }

    /*
     * 예약 대기를 이벤트별로 모아 한 번씩 조회하며 DB 연결과 발급 작업자를 점유하지 않는다.
     * 소진되면 대기 요청을 함께 끝내고 반납된 재고가 있으면 원래 처리 기한으로 다시 접수한다.
     */
    private void resolveReservations() {
        List<Pending> waiting = new ArrayList<>();
        awaitingReservations.drainTo(waiting);
        Map<Long, List<Pending>> byEvent = new LinkedHashMap<>();
        for (Pending pending : waiting) {
            if (!expireBeforeStart(pending)) {
                byEvent.computeIfAbsent(pending.request().eventId(), key -> new ArrayList<>()).add(pending);
            }
        }
        byEvent.forEach((eventId, requests) -> {
            if (!pollingEvents.add(eventId)) {
                requests.forEach(this::deferReservation);
                return;
            }
            dispatchEvent(CouponLogContext.shared(requests.stream().map(Pending::logContext).toList()),
                    () -> resolveEvent(eventId, requests));
        });
    }

    private void resolveEvent(long eventId, List<Pending> requests) {
        try {
            int availability = sequenceService.retryAvailability(eventId);
            if (availability == 0) {
                soldOutNotifications.request(eventId);
                requests.forEach(pending -> pending.future().completeExceptionally(
                        new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED)));
            } else if (availability == 1) {
                for (Pending pending : requests) {
                    if (!expireBeforeStart(pending) && (!running || !queue.offer(pending))) {
                        pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY));
                    }
                }
            } else {
                requests.forEach(this::deferReservation);
            }
        } catch (RuntimeException error) {
            requests.forEach(pending -> pending.future().completeExceptionally(error));
        } finally {
            pollingEvents.remove(eventId);
        }
    }

    // 스프링이 시작할 때 대기열을 만들고 작업자 스레드를 띄운다.
    @Override
    public void start() {
        queue = new ArrayBlockingQueue<>(queueCapacity);
        awaitingReservations = new ArrayBlockingQueue<>(queueCapacity);
        admission = new Semaphore(queueCapacity);
        eventTasks = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("coupon-event-", 0).factory());
        running = true;
        reservationWaiter = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("coupon-reservation-waiter").daemon(true).factory());
        reservationWaiter.scheduleWithFixedDelay(this::resolveReservations, 10, 10, TimeUnit.MILLISECONDS);
        for (int i = 0; i < workerCount; i++) {
            workers.add(Thread.ofPlatform().name("coupon-batch-" + i).daemon(true).start(this::runWorker));
        }
        log.info("event=COUPON_BATCH_START_DONE workerCount={} maxSize={} queueCapacity={}", workerCount, maxSize, queueCapacity);
    }

    /*
     * 스프링이 종료할 때 새 요청 접수와 작업자 반복을 멈춘다.
     * 커밋 전 묶음은 취소하고 커밋 중인 작업에는 DB 호출 상한을 포함한 종료 시간을 준다.
     * 대기열에 남은 요청은 ISSUANCE_BUSY로 끝내 요청 스레드가 결과를 무한히 기다리지 않게 한다.
     */
    @Override
    public void stop() {
        running = false;
        active.forEach(Pending::cancel);
        long stopDeadline = System.nanoTime() + waitTimeout.plusSeconds(12).toNanos();
        if (reservationWaiter != null) {
            reservationWaiter.shutdown();
            try {
                reservationWaiter.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        for (Thread worker : workers) {
            try {
                long remaining = stopDeadline - System.nanoTime();
                if (remaining > 0) {
                    worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
        if (eventTasks != null) {
            eventTasks.shutdown();
            try {
                eventTasks.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        if (awaitingReservations != null) {
            Pending pending;
            while ((pending = awaitingReservations.poll()) != null) {
                pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY));
            }
        }
        if (queue != null) {
            List<Pending> left = new ArrayList<>();
            queue.drainTo(left);
            left.forEach(pending -> pending.future().completeExceptionally(new CouponException(CouponErrorCode.ISSUANCE_BUSY)));
        }
    }

    // HTTP의 graceful 종료 단계보다 뒤에 멈추고 DB·Redis 연결보다 먼저 정리한다.
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 2048;
    }

    // 작업자가 요청을 받고 있으면 true를 돌려주며 스프링이 종료 순서를 정할 때 쓴다.
    @Override
    public boolean isRunning() {
        return running;
    }

    // 대기열에 들어간 요청 한 건과 그 결과를 요청 스레드에 넘겨줄 통로다.
    private record Completion(Pending pending, CouponIssuanceOutcome outcome) { }

    private static final class Pending {
        private final CouponIssuanceRequest request;
        private final CompletableFuture<CouponIssuanceResult> future;
        private final long deadlineNanos;
        private final CouponLogContext logContext;
        private boolean cancelled;
        private CouponBatchDeadline deadline;

        private Pending(CouponIssuanceRequest request, CompletableFuture<CouponIssuanceResult> future, long deadlineNanos) {
            this.request = request;
            this.future = future;
            this.deadlineNanos = deadlineNanos;
            this.logContext = CouponLogContext.capture();
        }

        CouponLogContext logContext() { return logContext; }
        CouponIssuanceRequest request() { return request; }
        CompletableFuture<CouponIssuanceResult> future() { return future; }
        long deadlineNanos() { return deadlineNanos; }
        synchronized boolean cancelled() { return cancelled; }
        synchronized CouponBatchDeadline deadline() { return deadline; }

        synchronized void attach(CouponBatchDeadline deadline) {
            this.deadline = deadline;
            if (cancelled) {
                deadline.cancel();
            }
        }

        synchronized void detach() { deadline = null; }

        synchronized boolean cancel() {
            if (deadline != null && !deadline.cancel()) {
                return false;
            }
            cancelled = true;
            return true;
        }
    }
}
