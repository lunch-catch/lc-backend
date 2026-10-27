package com.lunchcatch.analytics.batch.service.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.analytics.batch.MemberUsage;
import com.lunchcatch.analytics.batch.repository.MemberUsageRepository;
import com.lunchcatch.analytics.event.repository.EventLogRepository;
import com.lunchcatch.coupon.contract.CouponStatsQueryService;
import com.lunchcatch.coupon.contract.DailyMemberCouponStats;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberUsageStepTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T16:00:00Z"),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private CouponStatsQueryService coupons;
    @Mock
    private EventLogRepository eventLog;
    @Mock
    private MemberUsageRepository repository;

    private MemberUsageStep step() {
        return new MemberUsageStep(coupons, eventLog, repository, CLOCK);
    }

    @Test
    void 이름은_지표_라벨로_쓰는_단계_이름이다() {
        assertThat(step().name()).isEqualTo("MemberUsageStep");
    }

    @Test
    void 사용만_있는_회원과_찜만_있는_회원과_둘_다_있는_회원을_각각_한_행으로_모은다() {
        when(coupons.dailyMemberStats(DAY)).thenReturn(List.of(
                new DailyMemberCouponStats(1, 2, 3000),
                new DailyMemberCouponStats(3, 1, 500)));
        when(eventLog.countWishesByMember(DAY)).thenReturn(Map.of(2L, 4L, 3L, 7L));

        MemberUsage usage = step().collect(DAY);

        assertThat(usage.rows()).containsExactly(
                new MemberUsage.Row(1, 0, 2, 3000),
                new MemberUsage.Row(2, 4, 0, 0),
                new MemberUsage.Row(3, 7, 1, 500));
    }

    @Test
    void 사용도_찜도_없으면_행이_없다() {
        when(coupons.dailyMemberStats(DAY)).thenReturn(List.of());
        when(eventLog.countWishesByMember(DAY)).thenReturn(Map.of());

        assertThat(step().collect(DAY).rows()).isEmpty();
    }

    @Test
    void 사용_수와_할인액은_쿠폰_집계_조회_값을_그대로_쓰고_찜은_이벤트_로그_값을_쓴다() {
        when(coupons.dailyMemberStats(DAY)).thenReturn(List.of(new DailyMemberCouponStats(9, 5, 18000)));
        when(eventLog.countWishesByMember(DAY)).thenReturn(Map.of(9L, 12L));

        assertThat(step().collect(DAY).rows())
                .containsExactly(new MemberUsage.Row(9, 12, 5, 18000));
    }

    @Test
    void 모으기는_자기_표를_쓰지_않는다() {
        when(coupons.dailyMemberStats(DAY)).thenReturn(List.of());
        when(eventLog.countWishesByMember(DAY)).thenReturn(Map.of());

        step().collect(DAY);

        verifyNoInteractions(repository);
    }

    @Test
    void 공개는_그날_행을_교체하고_쓴_행_수를_돌려준다() {
        MemberUsage collected = new MemberUsage(List.of(
                new MemberUsage.Row(1, 0, 2, 3000), new MemberUsage.Row(2, 4, 0, 0)));

        int written = step().publish(DAY, collected);

        assertThat(written).isEqualTo(2);
        verify(repository).replace(DAY, collected.rows(), LocalDateTime.of(2026, 10, 17, 1, 0));
    }

    @Test
    void 행이_없는_날도_공개하면_그날_행을_비우고_0을_돌려준다() {
        int written = step().publish(DAY, new MemberUsage(List.of()));

        assertThat(written).isZero();
        verify(repository).replace(DAY, List.of(), LocalDateTime.of(2026, 10, 17, 1, 0));
    }
}
