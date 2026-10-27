package com.lunchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.adserving.contract.AdservingStatsQueryService;
import com.lunchcatch.adserving.contract.MemberInvalidImpressionCount;
import com.lunchcatch.analytics.batch.InvalidMetrics;
import com.lunchcatch.analytics.batch.InvalidReasonCode;
import com.lunchcatch.analytics.batch.repository.InvalidAnalyticsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InvalidMetricsStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private AdservingStatsQueryService adserving;
    @Mock
    private InvalidAnalyticsRepository repository;

    private InvalidMetricsStep step() {
        return new InvalidMetricsStep(adserving, repository, CLOCK);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("InvalidMetricsStep");
    }

    @Test
    void 회원과_캠페인_조합마다_건수가_있는_사유만_한_행씩_만든다() {
        when(adserving.memberInvalidCounts(DAY)).thenReturn(List.of(
                new MemberInvalidImpressionCount(12L, 55L, 4, 2),
                new MemberInvalidImpressionCount(12L, 56L, 0, 7),
                new MemberInvalidImpressionCount(13L, 55L, 3, 0)));

        InvalidMetrics metrics = step().collect(DAY);

        assertThat(metrics.rows()).containsExactly(
                new InvalidMetrics.Row(12L, 55L, InvalidReasonCode.EXPIRED_OR_UNKNOWN, 4),
                new InvalidMetrics.Row(12L, 55L, InvalidReasonCode.NOT_OWNER, 2),
                new InvalidMetrics.Row(12L, 56L, InvalidReasonCode.NOT_OWNER, 7),
                new InvalidMetrics.Row(13L, 55L, InvalidReasonCode.EXPIRED_OR_UNKNOWN, 3));
    }

    @Test
    void 무효_노출이_없는_날은_행이_없다() {
        when(adserving.memberInvalidCounts(DAY)).thenReturn(List.of());

        assertThat(step().collect(DAY).rows()).isEmpty();
    }

    @Test
    void 모으기는_자기_표를_쓰지_않는다() {
        when(adserving.memberInvalidCounts(DAY)).thenReturn(List.of());

        step().collect(DAY);

        verifyNoInteractions(repository);
    }

    @Test
    void 공개는_그날_행을_교체하고_쓴_행_수를_돌려준다() {
        InvalidMetrics collected = new InvalidMetrics(List.of(
                new InvalidMetrics.Row(12L, 55L, InvalidReasonCode.NOT_OWNER, 2),
                new InvalidMetrics.Row(12L, 56L, InvalidReasonCode.NOT_OWNER, 7)));

        int written = step().publish(DAY, collected);

        assertThat(written).isEqualTo(2);
        verify(repository).replace(DAY, collected.rows(), LocalDateTime.of(2026, 10, 17, 1, 0));
    }
}
