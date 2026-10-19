package com.launchcatch.billing.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.PointCharged;
import com.launchcatch.billing.dto.RechargeReserveIncreased;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignFundingUpdater;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.Cause;
import com.launchcatch.campaign.contract.FundingJudgement;
import com.launchcatch.campaign.contract.Judgement;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class RechargeReservationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final Long OWNER_ID = 7L;
    private static final Long PAYMENT_ID = 42L;

    @Mock
    private CampaignQueryService campaignQuery;
    @Mock
    private CampaignFundingUpdater fundingUpdater;
    @Mock
    private PointAccountRepository accountRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private static Clock clockAt(LocalTime time) {
        return Clock.fixed(LocalDateTime.of(DAY, time).atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
    }

    private RechargeReservationService service(LocalTime now) {
        return new RechargeReservationService(clockAt(now), campaignQuery, fundingUpdater,
                accountRepository, ledgerRepository, planRepository, eventPublisher);
    }

    private static CampaignInfo campaign(long id, PauseReason reason, LocalDate start, LocalDate end, long dailyBudget) {
        return new CampaignInfo(id, OWNER_ID, 100L + id, CampaignStatus.PAUSED, 100, null, null, start, end,
                null, null, 0, null, 0, null, List.of(), null, null, null, reason, dailyBudget);
    }

    private static CampaignInfo campaign(long id, long dailyBudget) {
        return campaign(id, PauseReason.NO_POINTS, DAY.minusDays(1), DAY.plusDays(1), dailyBudget);
    }

    private void stubExistingPlan(long campaignId, long dailyBudget, long unitPrice) {
        when(planRepository.findByCampaignIdAndBusinessDate(campaignId, DAY))
                .thenReturn(Optional.of(BudgetDailyPlan.create(campaignId, OWNER_ID, DAY, dailyBudget, unitPrice, false, null)));
    }

    @Test
    void 설정_하루예산_10000_기존예약_3000_잔액_20000이면_새_예약액_10000이고_RESERVE는_7000만_기록한다() {
        CampaignInfo campaign = campaign(1L, 10000L);
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(campaign));
        when(campaignQuery.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign));
        stubExistingPlan(1L, 3000L, 10L);
        when(ledgerRepository.sumBalance(OWNER_ID)).thenReturn(20000L);
        when(ledgerRepository.insertRechargeReserveIfAbsent(eq(OWNER_ID), eq(PAYMENT_ID), eq(1L), eq(DAY), eq(-7000L), any()))
                .thenReturn(1);
        when(ledgerRepository.sumDeductForCampaignOn(1L, DAY)).thenReturn(500L);

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 20000L));

        verify(accountRepository).lockByOwnerId(OWNER_ID);
        verify(ledgerRepository).insertRechargeReserveIfAbsent(OWNER_ID, PAYMENT_ID, 1L, DAY, -7000L, LocalDateTime.of(DAY, LocalTime.of(12, 0)));
        verify(eventPublisher).publishEvent(new RechargeReserveIncreased(1L, DAY, 10000L));
        verify(fundingUpdater).applyFundingJudgement(
                new FundingJudgement(1L, OWNER_ID, DAY, Judgement.RESUME, Cause.RECHARGE, 10000L, 10L, 10000L, 500L));
    }

    @Test
    void 새_예약액이_단가보다_작으면_예약만_늘리고_재개를_통보하지_않는다() {
        CampaignInfo campaign = campaign(1L, 10000L);
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(campaign));
        when(campaignQuery.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign));
        stubExistingPlan(1L, 100L, 10000L);
        when(ledgerRepository.sumBalance(OWNER_ID)).thenReturn(200L);
        when(ledgerRepository.insertRechargeReserveIfAbsent(eq(OWNER_ID), eq(PAYMENT_ID), eq(1L), eq(DAY), eq(-200L), any()))
                .thenReturn(1);

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 200L));

        verify(eventPublisher).publishEvent(new RechargeReserveIncreased(1L, DAY, 300L));
        verifyNoInteractions(fundingUpdater);
    }

    @Test
    void 서빙이_하루_종일이라_어느_시각의_충전이든_재예약을_시도한다() {
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED))).thenReturn(List.of());

        service(LocalTime.of(0, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 10000L));
        service(LocalTime.of(13, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 10000L));
        service(LocalTime.of(23, 59, 59)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 10000L));

        verify(campaignQuery, times(3)).findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED));
    }

    @Test
    void 관리자나_점주가_중단한_캠페인과_집행_기간이_끝난_캠페인은_재예약하지_않는다() {
        CampaignInfo admin = campaign(1L, PauseReason.ADMIN, DAY.minusDays(1), DAY.plusDays(1), 10000L);
        CampaignInfo owner = campaign(2L, PauseReason.OWNER, DAY.minusDays(1), DAY.plusDays(1), 10000L);
        CampaignInfo ended = campaign(3L, PauseReason.NO_POINTS, DAY.minusDays(10), DAY.minusDays(1), 10000L);
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(admin, owner, ended));

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 10000L));

        verifyNoInteractions(accountRepository, planRepository, ledgerRepository, fundingUpdater, eventPublisher);
    }

    @Test
    void 그날_계획_행이_없는_캠페인은_건너뛴다() {
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(campaign(1L, 10000L)));
        when(planRepository.findByCampaignIdAndBusinessDate(1L, DAY)).thenReturn(Optional.empty());
        when(ledgerRepository.sumBalance(OWNER_ID)).thenReturn(10000L);

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 10000L));

        verifyNoInteractions(fundingUpdater, eventPublisher);
        verify(ledgerRepository, never()).insertRechargeReserveIfAbsent(any(), any(), any(), any(), any(Long.class), any());
    }

    /*
     * 후보를 읽은 뒤 점주 계정 잠금을 기다리는 동안 다른 트랜잭션이 같은 캠페인을 이미 재개시킨 경우다(#378).
     * findByIdForUpdate 로 다시 읽은 최신 상태가 더 이상 PAUSED(NO_POINTS)가 아니므로 그 캠페인은 건너뛰고,
     * 아직 멈춰 있는 다음 캠페인이 남은 잔액을 전부 받는다.
     */
    @Test
    void 잠근_뒤_이미_재개된_캠페인은_건너뛰고_남은_잔액을_다음_캠페인에_넘긴다() {
        CampaignInfo stale1 = campaign(1L, 10000L);
        CampaignInfo stale3 = campaign(3L, 10000L);
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(stale1, stale3));
        CampaignInfo resumed1 = new CampaignInfo(1L, OWNER_ID, 101L, CampaignStatus.ACTIVE, 100, null, null,
                DAY.minusDays(1), DAY.plusDays(1), null, null, 0, null, 0, null, List.of(), null, null, null,
                null, 10000L);
        when(campaignQuery.findByIdForUpdate(1L)).thenReturn(Optional.of(resumed1));
        when(campaignQuery.findByIdForUpdate(3L)).thenReturn(Optional.of(stale3));
        stubExistingPlan(1L, 3000L, 10L);
        stubExistingPlan(3L, 0L, 10L);
        when(ledgerRepository.sumBalance(OWNER_ID)).thenReturn(15000L);
        when(ledgerRepository.insertRechargeReserveIfAbsent(any(), any(), any(), any(), any(Long.class), any()))
                .thenReturn(1);
        when(ledgerRepository.sumDeductForCampaignOn(any(), any())).thenReturn(0L);

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 15000L));

        verify(ledgerRepository, never())
                .insertRechargeReserveIfAbsent(any(), any(), eq(1L), any(), any(Long.class), any());
        verify(ledgerRepository).insertRechargeReserveIfAbsent(
                OWNER_ID, PAYMENT_ID, 3L, DAY, -10000L, LocalDateTime.of(DAY, LocalTime.of(12, 0)));
    }

    // 후보를 읽은 뒤 관리자가 그 캠페인을 중단시킨 경우도 같은 방식으로 건너뛴다
    @Test
    void 잠근_뒤_관리자가_중단시킨_캠페인은_건너뛴다() {
        CampaignInfo stale = campaign(1L, 10000L);
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(stale));
        CampaignInfo pausedByAdmin = campaign(1L, PauseReason.ADMIN, DAY.minusDays(1), DAY.plusDays(1), 10000L);
        when(campaignQuery.findByIdForUpdate(1L)).thenReturn(Optional.of(pausedByAdmin));
        stubExistingPlan(1L, 3000L, 10L);
        when(ledgerRepository.sumBalance(OWNER_ID)).thenReturn(20000L);

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 20000L));

        verifyNoInteractions(fundingUpdater, eventPublisher);
        verify(ledgerRepository, never()).insertRechargeReserveIfAbsent(any(), any(), any(), any(), any(Long.class), any());
    }

    // 같은 결제의 이벤트가 두 번 와도 RESERVE 와 통보가 한 번뿐이다
    @Test
    void 이미_반영된_재예약이면_다시_기록하거나_통보하지_않는다() {
        CampaignInfo campaign = campaign(1L, 10000L);
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(campaign));
        when(campaignQuery.findByIdForUpdate(1L)).thenReturn(Optional.of(campaign));
        stubExistingPlan(1L, 3000L, 10L);
        when(ledgerRepository.sumBalance(OWNER_ID)).thenReturn(20000L);
        when(ledgerRepository.insertRechargeReserveIfAbsent(eq(OWNER_ID), eq(PAYMENT_ID), eq(1L), eq(DAY), eq(-7000L), any()))
                .thenReturn(0);

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 20000L));

        verifyNoInteractions(fundingUpdater, eventPublisher);
    }

    @Test
    void 여러_캠페인은_ID_오름차순으로_남은_잔액을_나눠_채운다() {
        CampaignInfo c3 = campaign(3L, 10000L);
        CampaignInfo c1 = campaign(1L, 10000L);
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(c3, c1));
        when(campaignQuery.findByIdForUpdate(1L)).thenReturn(Optional.of(c1));
        when(campaignQuery.findByIdForUpdate(3L)).thenReturn(Optional.of(c3));
        stubExistingPlan(1L, 0L, 10L);
        stubExistingPlan(3L, 0L, 10L);
        when(ledgerRepository.sumBalance(OWNER_ID)).thenReturn(15000L);
        when(ledgerRepository.insertRechargeReserveIfAbsent(any(), any(), any(), any(), any(Long.class), any())).thenReturn(1);
        when(ledgerRepository.sumDeductForCampaignOn(any(), any())).thenReturn(0L);

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 15000L));

        // 1 이 먼저 10000 을 채우고 남은 5000 을 3 이 채운다
        verify(ledgerRepository).insertRechargeReserveIfAbsent(OWNER_ID, PAYMENT_ID, 1L, DAY, -10000L, LocalDateTime.of(DAY, LocalTime.of(12, 0)));
        verify(ledgerRepository).insertRechargeReserveIfAbsent(OWNER_ID, PAYMENT_ID, 3L, DAY, -5000L, LocalDateTime.of(DAY, LocalTime.of(12, 0)));
    }

    @Test
    void 잔액이_음수면_0으로_보고_예약을_늘리지_않는다() {
        when(campaignQuery.findByOwnerIdAndStatusIn(OWNER_ID, List.of(CampaignStatus.PAUSED)))
                .thenReturn(List.of(campaign(1L, 10000L)));
        stubExistingPlan(1L, 3000L, 10L);
        when(ledgerRepository.sumBalance(OWNER_ID)).thenReturn(-500L);

        service(LocalTime.of(12, 0)).reserve(new PointCharged(OWNER_ID, PAYMENT_ID, 0L));

        verifyNoInteractions(fundingUpdater, eventPublisher);
        verify(ledgerRepository, never()).insertRechargeReserveIfAbsent(any(), any(), any(), any(), any(Long.class), any());
    }
}
