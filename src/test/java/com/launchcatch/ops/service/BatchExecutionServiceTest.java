package com.launchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import com.launchcatch.ops.repository.BatchExecutionLogRepository;
import com.launchcatch.ops.scheduler.BatchAlert;
import com.launchcatch.ops.scheduler.StepOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 두 배치 서버가 같은 작업을 시도했을 때 하나만 이기는지, 그리고 멈춘 실행이 넘어가는지 본다.
 *
 * 판정은 전부 저장소가 돌려주는 "바뀐 행 수" 위에 있다. 그래서 1 과 0 을 주었을 때 서비스가
 * 서로 다르게 움직이는지가 이 테스트의 전부다.
 */
@ExtendWith(MockitoExtension.class)
class BatchExecutionServiceTest {

    private static final String ME = "batch-1";
    private static final String OTHER = "batch-2";
    private static final String JOB = "LOAD_DAILY_CANDIDATE";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 6);

    /** 2026-10-05T15:00Z 는 Asia/Seoul 로 2026-10-06 00:00 이다. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-05T15:00:00Z"), ClockConfig.ZONE);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 0, 0);
    private static final LocalDateTime STALE_BEFORE = NOW.minusMinutes(2);

    @Mock
    private BatchExecutionLogRepository logs;

    @Mock
    private BatchAlert alert;

    private BatchExecutionService service;

    @BeforeEach
    void setUp() {
        service = new BatchExecutionService(logs, alert, CLOCK, ME);
    }

    private static BatchExecutionLog row(BatchStatus status, String owner) {
        BatchExecutionLog row = BatchExecutionLog.running(JOB, DATE, owner);
        ReflectionTestUtils.setField(row, "status", status);
        return row;
    }

    @Test
    @DisplayName("영업일은 Asia/Seoul 기준이다")
    void 영업일() {
        assertThat(service.businessDate()).isEqualTo(DATE);
    }

    @Test
    @DisplayName("행을 넣으면 점유한 것이다")
    void 점유_성공() {
        assertThat(service.claim(JOB, DATE)).isTrue();
    }

    /*
     * 유일 제약 위반이 "다른 서버가 먼저 넣었다" 는 신호다.
     * 이것을 예외로 흘리면 늦게 온 서버가 기동 실패처럼 보이고, 무시하면 둘이 함께 돈다.
     */
    @Test
    @DisplayName("유일 제약에 걸리면 점유하지 못한 것이다")
    void 점유_실패() {
        when(logs.saveAndFlush(any(BatchExecutionLog.class)))
                .thenThrow(new DataIntegrityViolationException("uk_batch_execution_log_job_name_business_date"));

        assertThat(service.claim(JOB, DATE)).isFalse();
    }

    @Test
    @DisplayName("행이 없던 단계는 점유해서 시작한다")
    void 단계_시작() {
        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.STARTED);
    }

    @Test
    @DisplayName("이미 성공한 단계는 건너뛴다")
    void 단계_이미_완료() {
        when(logs.saveAndFlush(any(BatchExecutionLog.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.SUCCESS, OTHER)));

        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.ALREADY_DONE);
    }

    /*
     * 실패한 단계를 자동으로 되돌리지 않는다.
     * 되돌리면 같은 원인으로 계속 실패하면서 관리자가 볼 기록만 덮어쓴다.
     */
    @Test
    @DisplayName("실패로 닫힌 단계는 막는다")
    void 단계_실패로_막힘() {
        when(logs.saveAndFlush(any(BatchExecutionLog.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.FAILED, OTHER)));

        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.BLOCKED);
        verify(logs, never()).restartFailed(JOB, DATE, ME, NOW);
    }

    @Test
    @DisplayName("멈춘 실행 중 단계는 이어받아 시작한다")
    void 단계_이어받기() {
        when(logs.saveAndFlush(any(BatchExecutionLog.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.RUNNING, OTHER)));
        when(logs.takeOver(JOB, DATE, ME, NOW, STALE_BEFORE)).thenReturn(1);

        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.STARTED);
    }

    /*
     * 아직 살아 있는 서버가 들고 있으면 0건이다.
     * 그때 시작하면 같은 단계를 두 서버가 겹쳐 돈다.
     */
    @Test
    @DisplayName("살아 있는 실행 중 단계는 가져오지 못한다")
    void 단계_이어받기_실패() {
        when(logs.saveAndFlush(any(BatchExecutionLog.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(logs.findByJobNameAndBusinessDate(JOB, DATE))
                .thenReturn(Optional.of(row(BatchStatus.RUNNING, OTHER)));
        when(logs.takeOver(JOB, DATE, ME, NOW, STALE_BEFORE)).thenReturn(0);

        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.BLOCKED);
    }

    /** 점유에 실패했는데 행도 없는 경우다. 그 사이 누가 지웠다는 뜻이라 진행하지 않는다. */
    @Test
    @DisplayName("점유도 못 하고 행도 없으면 막는다")
    void 단계_행이_사라짐() {
        when(logs.saveAndFlush(any(BatchExecutionLog.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(logs.findByJobNameAndBusinessDate(JOB, DATE)).thenReturn(Optional.empty());

        assertThat(service.begin(JOB, DATE)).isEqualTo(StepOutcome.BLOCKED);
    }

    @Test
    @DisplayName("내 소유이면 생존 신호가 걸린다")
    void 생존_신호() {
        when(logs.renew(JOB, DATE, ME, NOW)).thenReturn(1);

        assertThat(service.renew(JOB, DATE)).isTrue();
    }

    /*
     * 0건은 이미 다른 서버가 이어받았다는 뜻이다.
     * 이 신호가 없으면 빼앗긴 서버가 남은 단계를 계속 돌아 집계가 두 번 더해진다.
     */
    @Test
    @DisplayName("내 소유가 아니면 생존 신호가 걸리지 않는다")
    void 생존_신호_상실() {
        when(logs.renew(JOB, DATE, ME, NOW)).thenReturn(0);

        assertThat(service.renew(JOB, DATE)).isFalse();
    }

    @Test
    @DisplayName("내가 들고 있는 행을 한 번에 갱신한다")
    void 소유한_행_갱신() {
        when(logs.renewOwned(ME, NOW)).thenReturn(2);

        assertThat(service.renewOwned()).isEqualTo(2);
    }

    @Test
    @DisplayName("성공은 사유 없이 닫는다")
    void 성공으로_닫는다() {
        service.succeed(JOB, DATE);

        verify(logs).close(JOB, DATE, BatchStatus.SUCCESS, null, NOW);
        verifyNoInteractions(alert);
    }

    @Test
    @DisplayName("실패는 사유와 함께 닫고 관리자에게 알린다")
    void 실패로_닫는다() {
        service.fail(JOB, DATE, "원본을 읽지 못했다");

        verify(logs).close(JOB, DATE, BatchStatus.FAILED, "원본을 읽지 못했다", NOW);
        verify(alert).failed(JOB, DATE, "원본을 읽지 못했다");
    }

    /*
     * failure_reason 이 500자 컬럼이다.
     * 스택트레이스가 섞인 사유는 쉽게 넘치고, 넘치면 저장이 실패해서 실패 기록 자체가 사라진다.
     */
    @Test
    @DisplayName("긴 사유는 컬럼 길이에 맞춰 자른다")
    void 긴_사유를_자른다() {
        String reason = "가".repeat(600);

        service.fail(JOB, DATE, reason);

        verify(logs).close(JOB, DATE, BatchStatus.FAILED, reason.substring(0, 500), NOW);
    }

    @Test
    @DisplayName("멈춘 행을 이어받고 관리자에게 알린다")
    void 멈춘_행을_이어받는다() {
        when(logs.findByStatusAndOwnerIdNotAndUpdatedAtBefore(BatchStatus.RUNNING, ME, STALE_BEFORE))
                .thenReturn(List.of(row(BatchStatus.RUNNING, OTHER)));
        when(logs.takeOver(JOB, DATE, ME, NOW, STALE_BEFORE)).thenReturn(1);

        assertThat(service.takeOverStale()).hasSize(1);
        verify(alert).takenOver(JOB, DATE, OTHER, ME);
    }

    /*
     * 찾은 뒤 조건부 UPDATE 로 한 번 더 판정한다.
     * 그 사이 원래 서버가 살아나 갱신했으면 0건이고, 이어받지 않는다.
     */
    @Test
    @DisplayName("그 사이 되살아난 행은 이어받지 않는다")
    void 되살아난_행은_건드리지_않는다() {
        when(logs.findByStatusAndOwnerIdNotAndUpdatedAtBefore(BatchStatus.RUNNING, ME, STALE_BEFORE))
                .thenReturn(List.of(row(BatchStatus.RUNNING, OTHER)));
        when(logs.takeOver(JOB, DATE, ME, NOW, STALE_BEFORE)).thenReturn(0);

        assertThat(service.takeOverStale()).isEmpty();
        verifyNoInteractions(alert);
    }

    @Test
    @DisplayName("실패한 행만 수동 재실행으로 되돌린다")
    void 수동_재실행() {
        when(logs.restartFailed(JOB, DATE, ME, NOW)).thenReturn(1);

        assertThat(service.retry(JOB, DATE)).isTrue();
    }

    /** 두 번 눌러도 두 번 돌지 않는다. 상태를 조건에 넣었기 때문이다. */
    @Test
    @DisplayName("실패 상태가 아니면 수동 재실행이 걸리지 않는다")
    void 수동_재실행_무효() {
        when(logs.restartFailed(JOB, DATE, ME, NOW)).thenReturn(0);

        assertThat(service.retry(JOB, DATE)).isFalse();
    }
}
