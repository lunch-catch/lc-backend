package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.contract.DailyCampaignCouponStats;
import com.launchcatch.coupon.contract.MemberCouponStats;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class CouponStatsQueryServiceImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    @Mock CouponEventRepository events;
    @Mock CouponUsageHistoryRepository histories;
    @InjectMocks CouponStatsQueryServiceImpl service;

    @Test
    void 영업일의_자정부터_다음_자정까지_집계한_값을_공개한다() {
        var row = mock(CouponEventRepository.CampaignStats.class);
        when(row.getCampaignId()).thenReturn(91L);
        when(row.getOpenCount()).thenReturn(1L);
        when(row.getIssuedCount()).thenReturn(100L);
        when(row.getUsedCount()).thenReturn(60L);
        when(row.getExpiredCount()).thenReturn(30L);
        when(events.findDailyCampaignStats(DAY, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(row));

        assertThat(service.dailyCampaignStats(DAY))
                .containsExactly(new DailyCampaignCouponStats(91, 1, 100, 60, 30));
    }

    @Test
    void 캠페인_집계가_없으면_빈_목록이다() {
        when(events.findDailyCampaignStats(DAY, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());

        assertThat(service.dailyCampaignStats(DAY)).isEmpty();
    }

    @Test
    void 영업일이_null이면_거절한다() {
        assertThatThrownBy(() -> service.dailyCampaignStats(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 회원과_영업일로_그날의_사용_수와_확정_할인액을_조회한다() {
        when(histories.findMemberStats(17L, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenReturn(new MemberCouponStats(5, 3_000_000_000L));

        assertThat(service.memberStats(17L, DAY)).isEqualTo(new MemberCouponStats(5, 3_000_000_000L));
    }

    @Test
    void 회원의_그날_사용_기록이_없으면_집계는_0이다() {
        when(histories.findMemberStats(17L, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenReturn(new MemberCouponStats(0, 0));

        assertThat(service.memberStats(17L, DAY)).isEqualTo(new MemberCouponStats(0, 0));
    }

    @Test
    void 회원_집계의_영업일이_null이면_거절한다() {
        assertThatThrownBy(() -> service.memberStats(17L, null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 회원_ID가_양수가_아니면_거절한다(long memberId) {
        assertThatThrownBy(() -> service.memberStats(memberId, DAY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 캠페인_집계의_DB_장애를_빈_목록으로_바꾸지_않는다() {
        var failure = new DataAccessResourceFailureException("stats query failed");
        when(events.findDailyCampaignStats(DAY, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.dailyCampaignStats(DAY)).isSameAs(failure);
    }

    @Test
    void 회원_집계의_DB_장애를_0으로_바꾸지_않는다() {
        var failure = new DataAccessResourceFailureException("summary query failed");
        when(histories.findMemberStats(17L, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.memberStats(17L, DAY)).isSameAs(failure);
    }
}
