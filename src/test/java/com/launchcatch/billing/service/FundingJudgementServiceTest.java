package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignFundingUpdater;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.Cause;
import com.launchcatch.campaign.contract.FundingJudgement;
import com.launchcatch.campaign.contract.Judgement;
import com.launchcatch.campaign.contract.PauseReason;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FundingJudgementServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final long UNIT = 10L;
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Mock
    private CampaignQueryService campaignQuery;
    @Mock
    private CampaignFundingUpdater fundingUpdater;
    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;

    private FundingJudgementService service;

    @BeforeEach
    void setUp() {
        service = new FundingJudgementService(campaignQuery, fundingUpdater, planRepository, ledgerRepository);
    }

    private static CampaignInfo campaign(long id, CampaignStatus status, PauseReason reason) {
        return new CampaignInfo(id, 7L, 100L + id, status, 100, null, null, null, null, null, null, 0, null, 0,
                null, List.of(), null, null, CHANGED_AT, reason, 10000L);
    }

    private static BudgetDailyPlan plan(long campaignId, long reserved) {
        return BudgetDailyPlan.create(campaignId, 7L, DAY, reserved, UNIT, false, CHANGED_AT);
    }

    private List<FundingJudgement> judgements(int times) {
        ArgumentCaptor<FundingJudgement> captor = ArgumentCaptor.forClass(FundingJudgement.class);
        verify(fundingUpdater, times(times)).applyFundingJudgement(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void 점주가_멈춘_캠페인만_있으면_아무것도_읽거나_통보하지_않는다() {
        when(campaignQuery.findServableOn(DAY))
                .thenReturn(List.of(campaign(1L, CampaignStatus.PAUSED, PauseReason.OWNER)));

        assertThat(service.judge(DAY)).isZero();
        verifyNoInteractions(fundingUpdater, planRepository, ledgerRepository);
    }

    @Test
    void 예약액이_단가보다_작으면_NO_POINTS이고_아니면_ACTIVE는_OK_멈춘_캠페인은_RESUME이다() {
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of(
                campaign(1L, CampaignStatus.ACTIVE, null),
                campaign(2L, CampaignStatus.ACTIVE, null),
                campaign(3L, CampaignStatus.PAUSED, PauseReason.NO_POINTS),
                campaign(4L, CampaignStatus.PAUSED, PauseReason.NO_POINTS),
                campaign(5L, CampaignStatus.PAUSED, PauseReason.OWNER)));
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L, 2L, 3L, 4L)))
                .thenReturn(List.of(plan(1L, 5L), plan(2L, 10000L), plan(3L, 10L), plan(4L, 0L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(1L, 2L, 3L, 4L), DAY))
                .thenReturn(List.of(new CampaignSpend(2L, 300L)));

        assertThat(service.judge(DAY)).isEqualTo(2);

        assertThat(judgements(4))
                .extracting(FundingJudgement::campaignId, FundingJudgement::judgement)
                .containsExactly(
                        tuple(1L, Judgement.NO_POINTS),
                        tuple(2L, Judgement.OK),
                        tuple(3L, Judgement.RESUME),
                        tuple(4L, Judgement.NO_POINTS));
    }

    @Test
    void 판정에는_51행의_필수_값을_함께_싣는다() {
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of(campaign(2L, CampaignStatus.ACTIVE, null)));
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(2L))).thenReturn(List.of(plan(2L, 3000L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(2L), DAY)).thenReturn(List.of(new CampaignSpend(2L, 300L)));

        service.judge(DAY);

        assertThat(judgements(1)).containsExactly(new FundingJudgement(
                2L, 7L, DAY, Judgement.OK, Cause.DAILY, 3000L, UNIT, 10000L, 300L));
    }

    @Test
    void 계획이_없는_캠페인은_판정하지_않는다() {
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of(
                campaign(1L, CampaignStatus.ACTIVE, null),
                campaign(2L, CampaignStatus.ACTIVE, null)));
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L, 2L))).thenReturn(List.of(plan(2L, 5L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(1L, 2L), DAY)).thenReturn(List.of());

        assertThat(service.judge(DAY)).isEqualTo(1);
        assertThat(judgements(1)).extracting(FundingJudgement::campaignId).containsExactly(2L);
    }
}
