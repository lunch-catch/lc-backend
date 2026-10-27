package com.lunchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.batch.MemberMetrics;
import com.lunchcatch.analytics.batch.repository.MemberAnalyticsRepository;
import com.lunchcatch.analytics.event.repository.EventLogRepository;
import com.lunchcatch.member.contract.MemberDailyStats;
import com.lunchcatch.member.contract.MemberStatsQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberMetricsStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private MemberStatsQueryService members;
    @Mock
    private EventLogRepository eventLog;
    @Mock
    private MemberAnalyticsRepository repository;

    private MemberMetricsStep step() {
        return new MemberMetricsStep(members, eventLog, repository, CLOCK);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("MemberMetricsStep");
    }

    @Test
    void 회원_집계_조회의_신규_누적_탈퇴_정지와_이벤트_로그의_활성_회원을_합친다() {
        when(members.dailyStats(DAY)).thenReturn(new MemberDailyStats(120, 5430, 48, 3));
        when(eventLog.countDistinctMembers(DAY)).thenReturn(910L);

        MemberMetrics metrics = step().collect(DAY);

        assertThat(metrics).isEqualTo(new MemberMetrics(120, 5430, 48, 910, 3));
    }

    @Test
    void 정지_회원이_있으면_정지_회원_수를_0으로_고정하지_않는다() {
        when(members.dailyStats(DAY)).thenReturn(new MemberDailyStats(0, 100, 0, 30));
        when(eventLog.countDistinctMembers(DAY)).thenReturn(0L);

        assertThat(step().collect(DAY).suspendedCount()).isEqualTo(30);
    }

    @Test
    void 이벤트가_없는_날은_활성_회원_0으로_모은다() {
        when(members.dailyStats(DAY)).thenReturn(new MemberDailyStats(0, 10, 0, 0));
        when(eventLog.countDistinctMembers(DAY)).thenReturn(0L);

        assertThat(step().collect(DAY).activeMemberCount()).isZero();
    }

    @Test
    void 모으기는_자기_표를_쓰지_않는다() {
        when(members.dailyStats(DAY)).thenReturn(new MemberDailyStats(1, 2, 0, 0));
        when(eventLog.countDistinctMembers(DAY)).thenReturn(1L);

        step().collect(DAY);

        verifyNoInteractions(repository);
    }

    @Test
    void 공개는_그날_행을_교체하고_쓴_행_수_1을_돌려준다() {
        MemberMetrics collected = new MemberMetrics(120, 5430, 48, 910, 3);

        int written = step().publish(DAY, collected);

        assertThat(written).isEqualTo(1);
        verify(repository).replace(DAY, collected, LocalDateTime.of(2026, 10, 17, 1, 0));
    }
}
