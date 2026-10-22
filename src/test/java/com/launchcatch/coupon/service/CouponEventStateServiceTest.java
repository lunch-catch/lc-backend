package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.QueryTimeoutException;

class CouponEventStateServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 10);
    private static final LocalDateTime NOW = BUSINESS_DATE.plusDays(1).atTime(0, 6);
    private static final LocalDateTime CHANGED_AT = NOW.minusMinutes(1);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);

    private final CouponEventRepository events = mock(CouponEventRepository.class);
    private final CouponEventStateService service = new CouponEventStateService(events, CLOCK);

    @ParameterizedTest
    @CsvSource({"true,ACTIVE", "false,PAUSED"})
    void 상태_변경은_수신한_영업일과_전이_시각을_그대로_사용한다(boolean servable, CouponEventStatus expected) {
        when(events.updateCampaignStatus(91, BUSINESS_DATE, expected, CHANGED_AT, NOW)).thenReturn(1);

        assertThat(service.changeCampaignStatus(91, BUSINESS_DATE, servable, CHANGED_AT)).isEqualTo(1);

        verify(events).updateCampaignStatus(91, BUSINESS_DATE, expected, CHANGED_AT, NOW);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void 종료는_여러_회차의_갱신_결과와_바꿀_회차가_없는_결과를_그대로_반환한다(int affectedRows) {
        when(events.endCampaign(91, CHANGED_AT, NOW)).thenReturn(affectedRows);

        assertThat(service.endCampaign(91, CHANGED_AT)).isEqualTo(affectedRows);

        verify(events).endCampaign(91, CHANGED_AT, NOW);
    }

    @Test
    void 상태_변경_저장_실패를_발행자에게_전파한다() {
        var failure = new QueryTimeoutException("coupon event update failed");
        when(events.updateCampaignStatus(91, BUSINESS_DATE, CouponEventStatus.PAUSED, CHANGED_AT, NOW))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.changeCampaignStatus(91, BUSINESS_DATE, false, CHANGED_AT))
                .isSameAs(failure);
    }

    @Test
    void 종료_저장_실패를_발행자에게_전파한다() {
        var failure = new QueryTimeoutException("coupon event end failed");
        when(events.endCampaign(91, CHANGED_AT, NOW)).thenThrow(failure);

        assertThatThrownBy(() -> service.endCampaign(91, CHANGED_AT)).isSameAs(failure);
    }
}
