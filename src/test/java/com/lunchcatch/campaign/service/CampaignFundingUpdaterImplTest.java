package com.lunchcatch.campaign.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lunchcatch.campaign.contract.Cause;
import com.lunchcatch.campaign.contract.FundingJudgement;
import com.lunchcatch.campaign.contract.Judgement;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignFundingUpdaterImplTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);

    @Mock
    private CampaignStatusService campaignStatusService;

    private CampaignFundingUpdaterImpl updater;

    @BeforeEach
    void setUp() {
        updater = new CampaignFundingUpdaterImpl(campaignStatusService);
    }

    @Test
    @DisplayName("NO_POINTS 판정은 pauseFromFunding을 부른다")
    void NO_POINTS는_pauseFromFunding을_부른다() {
        FundingJudgement judgement = judgement(Judgement.NO_POINTS, Cause.DAILY);

        updater.applyFundingJudgement(judgement);

        verify(campaignStatusService).pauseFromFunding(eq(1L), eq(BUSINESS_DATE), anyString());
        verify(campaignStatusService, never()).resumeFromFunding(anyLong(), eq(BUSINESS_DATE), anyString());
    }

    @Test
    @DisplayName("RESUME 판정은 resumeFromFunding을 부른다")
    void RESUME은_resumeFromFunding을_부른다() {
        FundingJudgement judgement = judgement(Judgement.RESUME, Cause.RECHARGE);

        updater.applyFundingJudgement(judgement);

        verify(campaignStatusService).resumeFromFunding(eq(1L), eq(BUSINESS_DATE), anyString());
        verify(campaignStatusService, never()).pauseFromFunding(anyLong(), eq(BUSINESS_DATE), anyString());
    }

    @Test
    @DisplayName("OK 판정은 아무 상태도 바꾸지 않는다")
    void OK는_아무것도_바꾸지_않는다() {
        FundingJudgement judgement = judgement(Judgement.OK, Cause.DAILY);

        updater.applyFundingJudgement(judgement);

        verifyNoInteractions(campaignStatusService);
    }

    private FundingJudgement judgement(Judgement judgement, Cause cause) {
        return new FundingJudgement(1L, 10L, BUSINESS_DATE, judgement, cause, 1000L, 100L, 50_000L, 5000L);
    }
}
