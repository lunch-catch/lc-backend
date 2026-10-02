package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.CouponIssueCondition;
import com.launchcatch.campaign.contract.CouponIssueConditionQueryService;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class CouponIssueServiceTest {

    private static final Long CAMPAIGN_ID = 7L;
    private static final Long MEMBER_ID = 42L;

    // 서울 기준 2026-10-02 12:30
    private static final Instant NOON = Instant.parse("2026-10-02T03:30:00Z");

    private static final LocalTime USABLE_FROM = LocalTime.of(11, 0);
    private static final LocalTime USABLE_TO = LocalTime.of(14, 0);

    private final CouponIssueConditionQueryService issueConditionQueryService =
            mock(CouponIssueConditionQueryService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private CouponIssueService serviceAt(Instant instant) {
        return new CouponIssueService(
                issueConditionQueryService, eventPublisher, Clock.fixed(instant, ClockConfig.ZONE));
    }

    private void givenCondition(LocalTime from, LocalTime to) {
        when(issueConditionQueryService.findActiveCondition(CAMPAIGN_ID))
                .thenReturn(Optional.of(new CouponIssueCondition(CAMPAIGN_ID, 50, from, to)));
    }

    @Test
    void 쓸_수_있는_시간대면_발급한다() {
        // given
        givenCondition(USABLE_FROM, USABLE_TO);

        // when
        boolean issued = serviceAt(NOON).issue(CAMPAIGN_ID, MEMBER_ID);

        // then
        assertThat(issued).isTrue();
    }

    @Test
    void 발급하면_쿠폰_발급_이벤트를_알린다() {
        // given
        givenCondition(USABLE_FROM, USABLE_TO);

        // when
        serviceAt(NOON).issue(CAMPAIGN_ID, MEMBER_ID);

        // then
        ArgumentCaptor<CouponIssuedEvent> captor = ArgumentCaptor.forClass(CouponIssuedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new CouponIssuedEvent(
                CAMPAIGN_ID, MEMBER_ID, LocalDateTime.of(2026, 10, 2, 12, 30)));
    }

    @Test
    void 활성_캠페인이_없으면_발급하지_않는다() {
        // given
        when(issueConditionQueryService.findActiveCondition(CAMPAIGN_ID)).thenReturn(Optional.empty());

        // when
        boolean issued = serviceAt(NOON).issue(CAMPAIGN_ID, MEMBER_ID);

        // then
        assertThat(issued).isFalse();
        verify(eventPublisher, never()).publishEvent(any(CouponIssuedEvent.class));
    }

    @Test
    void 시작_시각보다_이르면_발급하지_않는다() {
        // given
        givenCondition(LocalTime.of(13, 0), USABLE_TO);

        // when
        boolean issued = serviceAt(NOON).issue(CAMPAIGN_ID, MEMBER_ID);

        // then
        assertThat(issued).isFalse();
        verify(eventPublisher, never()).publishEvent(any(CouponIssuedEvent.class));
    }

    @Test
    void 시작_시각은_발급할_수_있다() {
        // given
        givenCondition(LocalTime.of(12, 30), USABLE_TO);

        // when
        boolean issued = serviceAt(NOON).issue(CAMPAIGN_ID, MEMBER_ID);

        // then
        assertThat(issued).isTrue();
    }

    @Test
    void 종료_시각은_발급할_수_없다() {
        // given
        givenCondition(USABLE_FROM, LocalTime.of(12, 30));

        // when
        boolean issued = serviceAt(NOON).issue(CAMPAIGN_ID, MEMBER_ID);

        // then
        assertThat(issued).isFalse();
    }

    @Test
    void 종료_시각을_지나면_발급하지_않는다() {
        // given
        givenCondition(USABLE_FROM, LocalTime.of(12, 0));

        // when
        boolean issued = serviceAt(NOON).issue(CAMPAIGN_ID, MEMBER_ID);

        // then
        assertThat(issued).isFalse();
    }
}
