package com.launchcatch.ops.service;

import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import com.launchcatch.ops.repository.BatchExecutionLogRepository;
import com.launchcatch.ops.scheduler.BatchAlert;
import com.launchcatch.ops.scheduler.StepOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 배치 실행의 점유와 생존, 이어받기를 맡는다 (배치 운영 문서 2장).
 *
 * 배치 서버 두 대가 모두 스케줄러를 켜고 같은 시각에 같은 작업을 시도한다. 한쪽만 켜 두면
 * 00:00 에 그 서버가 죽었을 때 사람이 전환해야 한다. 그래서 "둘 다 시도하고 하나만 이긴다" 를
 * 이 클래스가 만든다.
 *
 * 판정은 전부 조건부 쓰기로 한다. 읽고 나서 판단하면 두 서버가 같은 상태를 읽고 둘 다 자기
 * 것이라고 믿는 구간이 생긴다.
 */
@Service
public class BatchExecutionService {

    /*
     * 이 시간만큼 생존 신호가 멈추면 다른 서버가 이어받는다.
     * 갱신 주기(30초)의 네 배다. 한두 번 놓친 것으로는 빼앗기지 않고, 정말 죽었으면 2분 안에
     * 넘어간다.
     */
    private static final Duration STALE_AFTER = Duration.ofMinutes(2);

    /*
     * 자동 재실행 상한이다. chk_batch_retry 가 DB 에서 같은 값으로 묶는다.
     * 두 곳에 적히지만 한쪽만 바꾸면 저장이 거부되어 바로 드러난다. 코드에서만 세면
     * 두 서버가 겹쳐 돌 때 상한을 넘길 수 있어 DB 쪽이 마지막 방어다.
     */
    private static final int MAX_AUTO_RETRIES = 2;

    private final BatchExecutionLogRepository logs;
    private final BatchAlert alert;
    private final Clock clock;

    /** 이 배치 서버의 식별자다. 이어받으면 행의 소유자가 이 값으로 바뀐다. */
    private final String ownerId;

    public BatchExecutionService(BatchExecutionLogRepository logs,
                                 BatchAlert alert,
                                 Clock clock,
                                 @Value("${launchcatch.batch.owner-id}") String ownerId) {
        this.logs = logs;
        this.alert = alert;
        this.clock = clock;
        this.ownerId = ownerId;
    }

    /** 영업일은 Asia/Seoul 기준이다. 관계형 DB 의 날짜 함수를 쓰지 않는다. */
    public LocalDate businessDate() {
        return LocalDate.now(clock);
    }

    /*
     * 그 작업을 이 서버 것으로 점유한다. 이미 행이 있으면 false 다.
     *
     * 트랜잭션을 걸지 않는다. 유일 제약 위반을 판정에 쓰는데, 이 메서드가 트랜잭션 안이면
     * 그 예외가 트랜잭션을 되돌릴 것으로 표시해서 잡고 넘어가도 커밋에서 터진다. 삽입만
     * 저장소 쪽에서 자기 트랜잭션으로 돌린다(saveAndFlush 의 REQUIRES_NEW).
     */
    public boolean claim(String jobName, LocalDate businessDate) {
        try {
            logs.saveAndFlush(BatchExecutionLog.running(jobName, businessDate, ownerId));
            return true;
        } catch (DataIntegrityViolationException alreadyClaimed) {
            return false;
        }
    }

    /*
     * 한 단계를 시작한다.
     *
     * 행이 없으면 점유한다. 이미 있으면 상태를 본다. 성공한 단계는 건너뛰고, 실패한 단계는
     * 막는다. 실패를 자동으로 되돌리지 않는 것이 규칙이다. 관리자가 수동 재실행을 눌러야 한다.
     * 멈춘 실행 중 행은 이어받아 다시 돈다.
     */
    public StepOutcome begin(String jobName, LocalDate businessDate) {
        if (claim(jobName, businessDate)) {
            return StepOutcome.STARTED;
        }
        Optional<BatchExecutionLog> existing = logs.findByJobNameAndBusinessDate(jobName, businessDate);
        if (existing.isEmpty()) {
            return StepOutcome.BLOCKED;
        }
        return switch (existing.get().getStatus()) {
            case SUCCESS -> StepOutcome.ALREADY_DONE;
            case FAILED -> StepOutcome.BLOCKED;
            case RUNNING -> takeOver(jobName, businessDate) ? StepOutcome.STARTED : StepOutcome.BLOCKED;
        };
    }

    /*
     * 생존 신호를 보낸다. 내 소유가 아니면 false 다.
     *
     * false 는 이미 다른 서버가 이어받았다는 뜻이다. 그때는 다음 단계로 가지 않고 멈춘다.
     * 두 서버가 같은 단계를 동시에 돌면 멱등이라도 집계가 두 번 더해질 수 있다.
     */
    @Transactional
    public boolean renew(String jobName, LocalDate businessDate) {
        return logs.renew(jobName, businessDate, ownerId, now()) == 1;
    }

    /** 내가 들고 있는 실행 중 행을 모두 갱신한다. 30초마다 도는 생존 신호가 부른다. */
    @Transactional
    public int renewOwned() {
        return logs.renewOwned(ownerId, now());
    }

    @Transactional
    public void succeed(String jobName, LocalDate businessDate) {
        logs.close(jobName, businessDate, BatchStatus.SUCCESS, null, now());
    }

    /*
     * 실패로 닫고 관리자에게 알린다.
     * 사유는 500자 컬럼이라 잘라 넣는다. 넘치면 저장이 실패해서 실패 기록 자체가 사라진다.
     */
    @Transactional
    public void fail(String jobName, LocalDate businessDate, String reason) {
        logs.close(jobName, businessDate, BatchStatus.FAILED, shorten(reason), now());
        alert.failed(jobName, businessDate, reason);
    }

    /*
     * 멈춘 행을 찾아 이어받는다. 이어받은 행을 돌려준다.
     *
     * 내 소유는 찾지 않는다. 내가 들고 있는데 갱신이 멈췄다면 내 생존 신호가 막힌 것이고,
     * 그것을 내가 다시 가져오는 것은 아무 의미가 없다.
     */
    @Transactional
    public List<BatchExecutionLog> takeOverStale() {
        LocalDateTime staleBefore = now().minus(STALE_AFTER);
        List<BatchExecutionLog> taken = new ArrayList<>();
        for (BatchExecutionLog stale : logs.findByStatusAndOwnerIdNotAndUpdatedAtBefore(
                BatchStatus.RUNNING, ownerId, staleBefore)) {
            if (logs.takeOver(stale.getJobName(), stale.getBusinessDate(), ownerId, now(), staleBefore) == 1) {
                alert.takenOver(stale.getJobName(), stale.getBusinessDate(), stale.getOwnerId(), ownerId);
                taken.add(stale);
            }
        }
        return taken;
    }

    /*
     * 자동 재실행 한 번을 기록한다. 상한에 닿았으면 false 다.
     *
     * 횟수를 코드가 들고 있지 않고 행이 들고 있다. 이어받은 서버도 같은 행을 보므로 서버가
     * 바뀌어도 상한이 이어진다.
     */
    @Transactional
    public boolean recordRetry(String jobName, LocalDate businessDate, String reason) {
        return logs.recordRetry(jobName, businessDate, shorten(reason), MAX_AUTO_RETRIES, now()) == 1;
    }

    /*
     * 관리자의 수동 재실행이다. 실패한 행만 실행 중으로 되돌린다.
     * 상태를 조건에 넣었으므로 두 번 눌러도 한 번만 걸린다.
     */
    @Transactional
    public boolean retry(String jobName, LocalDate businessDate) {
        return logs.restartFailed(jobName, businessDate, ownerId, now()) == 1;
    }

    /** 멈춘 실행 중 행 하나를 이어받는다. 조건에 걸린 서버만 1건을 받는다. */
    private boolean takeOver(String jobName, LocalDate businessDate) {
        LocalDateTime at = now();
        return logs.takeOver(jobName, businessDate, ownerId, at, at.minus(STALE_AFTER)) == 1;
    }

    private String shorten(String reason) {
        return reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
