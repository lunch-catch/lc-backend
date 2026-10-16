package com.launchcatch.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import com.launchcatch.ops.repository.BatchExecutionLogRepository;
import com.launchcatch.ops.scheduler.StepOutcome;
import com.launchcatch.ops.service.BatchExecutionService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 점유와 이어받기를 실제 MySQL 에서 확인한다.
 *
 * 단위 테스트는 저장소를 목으로 두므로 아무것도 보장하지 않는 것이 셋이다. 엔티티와 테이블의
 * 매핑, JPQL 이 실제로 번역되는지, 그리고 batch_execution_log 의 CHECK 제약이다. 상태와
 * 종료 시각, 실패 사유는 짝을 요구하는 제약이 걸려 있어 한쪽만 바꾸면 저장이 거부된다.
 * 그 거부는 배치가 실패를 기록하려는 순간에 터지므로, 실패 기록 자체가 사라진다.
 */
class BatchExecutionIntegrationTest extends AbstractOpsIntegrationTest {

    private static final String ME = IntegrationTestSupport.BATCH_OWNER;
    private static final String OTHER = "batch-2";
    private static final String JOB = "LOAD_DAILY_CANDIDATE";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 6);

    @Autowired
    private BatchExecutionService service;

    @Autowired
    private BatchExecutionLogRepository logs;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM batch_execution_log");
    }

    private BatchExecutionLog reload(String jobName) {
        return logs.findByJobNameAndBusinessDate(jobName, DATE).orElseThrow();
    }

    /** 생존 신호가 멈춘 상태를 만든다. 시각만 과거로 밀면 이어받기 조건에 걸린다. */
    private void makeStale(String jobName) {
        jdbc.update("UPDATE batch_execution_log SET updated_at = updated_at - INTERVAL 5 MINUTE"
                + " WHERE job_name = ?", jobName);
    }

    private void claimAs(String jobName, String owner) {
        logs.saveAndFlush(BatchExecutionLog.running(jobName, DATE, owner));
    }

    /** 두 서버가 같은 순간에 점유를 시도한 결과를 모은다. */
    private List<Boolean> claimTogether() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Callable<Boolean> attempt = () -> {
                gate.await();
                return service.claim(JOB, DATE);
            };
            Future<Boolean> left = pool.submit(attempt);
            Future<Boolean> right = pool.submit(attempt);
            gate.countDown();
            return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /*
     * 두 서버가 같은 시각에 시도하는 것이 정상 운영이다.
     *
     * 유일 제약이 둘 중 하나를 떨어뜨리는데, 경합 상황에서 InnoDB 가 중복 키 대신 다른 예외를
     * 내면 지는 쪽이 기동 실패처럼 보인다. 순차 시도로는 그 구간을 지나가지 않는다.
     */
    @Test
    @DisplayName("동시에 점유를 시도하면 정확히 하나만 이긴다")
    void 동시_점유() throws Exception {
        assertThat(claimTogether()).containsExactlyInAnyOrder(true, false);
    }

    /*
     * 유일 제약이 중복 실행을 막는다.
     * 두 서버가 같은 시각에 시도하는 것이 정상이라, 지는 쪽이 조용히 비켜야 한다.
     */
    @Test
    @DisplayName("같은 작업과 영업일은 한 번만 점유된다")
    void 점유는_한_번만() {
        assertThat(service.claim(JOB, DATE)).isTrue();
        assertThat(service.claim(JOB, DATE)).isFalse();
    }

    /*
     * chk_batch_finished 가 "RUNNING 이 아니면 finished_at 이 있어야 한다" 를 요구한다.
     * 상태만 바꾸는 UPDATE 를 쓰면 여기서 저장이 거부된다.
     */
    @Test
    @DisplayName("성공으로 닫으면 종료 시각이 남고 사유와 처리 건수는 비어 있다")
    void 성공으로_닫는다() {
        service.claim(JOB, DATE);

        service.succeed(JOB, DATE);

        BatchExecutionLog row = reload(JOB);
        assertThat(row.getStatus()).isEqualTo(BatchStatus.SUCCESS);
        assertThat(row.getFinishedAt()).isNotNull();
        assertThat(row.getFailureReason()).isNull();
        assertThat(row.getProcessedCount()).isNull();
    }

    /** #359. 원천 행을 보지 못하는 호출자(분석)가 집계 결과로 쓴 행 수를 처리 건수로 남긴다. */
    @Test
    @DisplayName("건수를 주고 성공으로 닫으면 처리 건수가 그 값으로 남는다")
    void 성공으로_닫으면_처리_건수가_남는다() {
        service.claim(JOB, DATE);

        service.succeed(JOB, DATE, 120);

        assertThat(reload(JOB).getProcessedCount()).isEqualTo(120);
    }

    /** chk_batch_failure_reason 이 FAILED 와 사유를 짝으로 요구한다. */
    @Test
    @DisplayName("실패로 닫으면 사유와 종료 시각이 함께 남는다")
    void 실패로_닫는다() {
        service.claim(JOB, DATE);

        service.fail(JOB, DATE, "원본을 읽지 못했다");

        BatchExecutionLog row = reload(JOB);
        assertThat(row.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(row.getFailureReason()).isEqualTo("원본을 읽지 못했다");
        assertThat(row.getFinishedAt()).isNotNull();
    }

    /*
     * 수동 재실행은 새 행을 넣지 않고 기존 행을 되돌린다.
     * 두 CHECK 가 짝을 요구하므로 종료 시각과 사유를 함께 비워야 한다.
     */
    @Test
    @DisplayName("수동 재실행은 실패한 행을 되돌리고 두 번은 걸리지 않는다")
    void 수동_재실행() {
        service.claim(JOB, DATE);
        service.fail(JOB, DATE, "원본을 읽지 못했다");

        assertThat(service.retry(JOB, DATE)).isTrue();

        BatchExecutionLog row = reload(JOB);
        assertThat(row.getStatus()).isEqualTo(BatchStatus.RUNNING);
        assertThat(row.getFinishedAt()).isNull();
        assertThat(row.getFailureReason()).isNull();
        assertThat(row.getOwnerId()).isEqualTo(ME);
        assertThat(service.retry(JOB, DATE)).isFalse();
    }

    /** #359. 지난 시도의 시작 시각이 남으면 운영 대시보드가 지난 시도의 소요 시간을 보여 준다. */
    @Test
    @DisplayName("수동 재실행은 시작 시각을 새 시도의 시각으로 되돌린다")
    void 수동_재실행이_시작_시각을_새로_한다() {
        service.claim(JOB, DATE);
        LocalDateTime startedAt = reload(JOB).getCreatedAt();
        service.fail(JOB, DATE, "원본을 읽지 못했다");

        assertThat(service.retry(JOB, DATE)).isTrue();

        assertThat(reload(JOB).getCreatedAt()).isAfter(startedAt);
    }

    @Test
    @DisplayName("생존 신호는 내 소유일 때만 걸린다")
    void 생존_신호() {
        claimAs(JOB, OTHER);

        assertThat(service.renew(JOB, DATE)).isFalse();
    }

    @Test
    @DisplayName("멈춘 행은 이어받아 소유자가 바뀐다")
    void 멈춘_행을_이어받는다() {
        claimAs(JOB, OTHER);
        makeStale(JOB);

        assertThat(service.takeOverStale()).hasSize(1);
        assertThat(reload(JOB).getOwnerId()).isEqualTo(ME);
    }

    /** 2분이 지나지 않은 행은 아직 살아 있는 것으로 본다. */
    @Test
    @DisplayName("갱신이 멈추지 않은 행은 이어받지 않는다")
    void 살아_있는_행은_두고_간다() {
        claimAs(JOB, OTHER);

        assertThat(service.takeOverStale()).isEmpty();
        assertThat(reload(JOB).getOwnerId()).isEqualTo(OTHER);
    }

    @Test
    @DisplayName("이미 성공한 단계는 다시 시작하지 않는다")
    void 성공한_단계는_건너뛴다() {
        service.claim(JOB, DATE);
        service.succeed(JOB, DATE);

        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.ALREADY_DONE);
    }

    /*
     * 상한을 WHERE 에서 판정한다.
     * 코드에서만 세면 두 서버가 겹쳐 돌 때 셋이 될 수 있고, 그때는 chk_batch_retry 위반으로
     * 저장이 거부되어 재실행 기록 자체가 사라진다.
     */
    @Test
    @DisplayName("자동 재실행은 횟수를 올리고 상한에서 멈춘다")
    void 자동_재실행_상한() {
        service.claim(JOB, DATE);

        assertThat(service.recordRetry(JOB, DATE, "교착")).isTrue();
        assertThat(reload(JOB).getRetryCount()).isEqualTo(1);
        assertThat(service.recordRetry(JOB, DATE, "락 대기 시간 초과")).isTrue();
        assertThat(reload(JOB).getRetryCount()).isEqualTo(2);
        assertThat(service.recordRetry(JOB, DATE, "교착")).isFalse();
        assertThat(reload(JOB).getRetryCount()).isEqualTo(2);
        assertThat(reload(JOB).getRetryReason()).isEqualTo("락 대기 시간 초과");
    }

    /*
     * 재실행 예산은 그 행을 들고 있는 서버만 쓸 수 있다.
     * 소유자 조건이 없으면 임대를 잃은 서버가 새 소유자의 예산을 깎고 사유를 덮어쓴다.
     */
    @Test
    @DisplayName("내 소유가 아닌 행에는 자동 재실행을 기록하지 않는다")
    void 남의_행에는_재실행을_기록하지_않는다() {
        claimAs(JOB, OTHER);

        assertThat(service.recordRetry(JOB, DATE, "교착")).isFalse();
        assertThat(reload(JOB).getRetryCount()).isZero();
    }

    /*
     * 수동 재실행이 재실행 횟수를 되돌리지 않으면, 그 뒤의 자동 재실행이 시작부터 상한이라
     * 한 번도 돌지 않고 바로 실패한다. chk_batch_retry 가 횟수와 사유를 짝으로 요구한다.
     */
    @Test
    @DisplayName("수동 재실행은 자동 재실행 횟수도 0으로 되돌린다")
    void 수동_재실행이_횟수를_되돌린다() {
        service.claim(JOB, DATE);
        service.recordRetry(JOB, DATE, "교착");
        service.recordRetry(JOB, DATE, "교착");
        service.fail(JOB, DATE, "두 번 다시 돌려도 실패했다");

        assertThat(service.retry(JOB, DATE)).isTrue();

        BatchExecutionLog row = reload(JOB);
        assertThat(row.getRetryCount()).isZero();
        assertThat(row.getRetryReason()).isNull();
    }

    /*
     * 재실행(rerun)은 retry와 달리 실패한 행도 되돌린다. restartFailed와 같은 리셋 필드를 쓰므로
     * chk_batch_failure_reason, chk_batch_finished 가 짝을 요구하는 것도 같이 확인된다.
     */
    @Test
    @DisplayName("재실행은 실패한 행을 되돌리고 두 번은 걸리지 않는다")
    void 재실행_FAILED() {
        service.claim(JOB, DATE);
        service.fail(JOB, DATE, "원본을 읽지 못했다");

        assertThat(service.rerun(JOB, DATE)).isTrue();

        BatchExecutionLog row = reload(JOB);
        assertThat(row.getStatus()).isEqualTo(BatchStatus.RUNNING);
        assertThat(row.getFinishedAt()).isNull();
        assertThat(row.getFailureReason()).isNull();
        assertThat(row.getOwnerId()).isEqualTo(ME);
        assertThat(service.rerun(JOB, DATE)).isFalse();
    }

    /** retry는 FAILED만 다뤄서 이 행을 못 되돌린다. rerun만 되는 새 경로다(#339). */
    @Test
    @DisplayName("재실행은 이미 성공한 행도 되돌린다")
    void 재실행_SUCCESS() {
        service.claim(JOB, DATE);
        service.succeed(JOB, DATE);

        assertThat(service.retry(JOB, DATE)).isFalse();
        assertThat(service.rerun(JOB, DATE)).isTrue();

        BatchExecutionLog row = reload(JOB);
        assertThat(row.getStatus()).isEqualTo(BatchStatus.RUNNING);
        assertThat(row.getFinishedAt()).isNull();
        assertThat(row.getFailureReason()).isNull();
        assertThat(row.getOwnerId()).isEqualTo(ME);
    }

    /*
     * #359. 분석이 매일 밤 전날 FAILED나 SUCCESS로 끝난 영업일을 사람 없이 재실행해도, 그
     * 시도의 시작 시각과 처리 건수가 지난 시도의 것으로 뒤섞이지 않아야 한다.
     */
    @Test
    @DisplayName("재실행은 시작 시각을 새로 하고 처리 건수를 비운다")
    void 재실행이_시작_시각과_처리_건수를_새로_한다() {
        service.claim(JOB, DATE);
        LocalDateTime startedAt = reload(JOB).getCreatedAt();
        service.succeed(JOB, DATE, 77);

        assertThat(service.rerun(JOB, DATE)).isTrue();

        BatchExecutionLog row = reload(JOB);
        assertThat(row.getCreatedAt()).isAfter(startedAt);
        assertThat(row.getProcessedCount()).isNull();
    }

    @Test
    @DisplayName("실행 중인 행은 재실행으로 되돌리지 않는다")
    void 재실행_RUNNING은_무효() {
        service.claim(JOB, DATE);

        assertThat(service.rerun(JOB, DATE)).isFalse();
        assertThat(reload(JOB).getStatus()).isEqualTo(BatchStatus.RUNNING);
    }

    /*
     * 서버가 죽으면 묶음 행과 단계 행이 함께 오래된다. 이어받기는 둘을 함께 가져오고
     * updated_at 을 지금으로 바꾼다. 그 뒤 묶음을 다시 도는 길에서 그 단계를 시작할 수 있어야
     * "SUCCESS 가 아닌 첫 단계부터 다시 실행한다" 가 성립한다.
     */
    @Test
    @DisplayName("이어받은 단계를 다시 시작할 수 있다")
    void 이어받은_단계를_다시_시작한다() {
        claimAs(JOB, OTHER);
        makeStale(JOB);
        assertThat(service.takeOverStale()).hasSize(1);

        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.STARTED);
    }

    @Test
    @DisplayName("멈춘 실행 중 단계는 이어받아 시작한다")
    void 멈춘_단계를_이어받아_시작한다() {
        claimAs(JOB, OTHER);
        makeStale(JOB);

        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.STARTED);
        assertThat(reload(JOB).getOwnerId()).isEqualTo(ME);
    }
}
