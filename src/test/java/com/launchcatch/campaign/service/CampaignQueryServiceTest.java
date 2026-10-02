package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.CouponIssueCondition;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CampaignQueryServiceTest {

    private static final Long CAMPAIGN_ID = 7L;

    private final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    private final CampaignQueryService campaignQueryService = new CampaignQueryService(campaignRepository);

    @Test
    void 활성_캠페인의_발급_조건을_돌려준다() {
        // given
        CouponIssueCondition condition =
                new CouponIssueCondition(CAMPAIGN_ID, 50, LocalTime.of(11, 0), LocalTime.of(14, 0));
        when(campaignRepository.findActiveCondition(CAMPAIGN_ID)).thenReturn(Optional.of(condition));

        // when
        Optional<CouponIssueCondition> found = campaignQueryService.findActiveCondition(CAMPAIGN_ID);

        // then
        assertThat(found).contains(condition);
    }

    @Test
    void 활성_캠페인이_없으면_빈_값을_돌려준다() {
        // given
        when(campaignRepository.findActiveCondition(anyLong())).thenReturn(Optional.empty());

        // when
        Optional<CouponIssueCondition> found = campaignQueryService.findActiveCondition(CAMPAIGN_ID);

        // then
        assertThat(found).isEmpty();
    }

    @Test
    void 다른_캠페인의_조건을_돌려주지_않는다() {
        // given
        CouponIssueCondition other =
                new CouponIssueCondition(99L, 50, LocalTime.of(11, 0), LocalTime.of(14, 0));
        when(campaignRepository.findActiveCondition(99L)).thenReturn(Optional.of(other));

        // when
        Optional<CouponIssueCondition> found = campaignQueryService.findActiveCondition(CAMPAIGN_ID);

        // then
        assertThat(found).isEmpty();
    }
}
