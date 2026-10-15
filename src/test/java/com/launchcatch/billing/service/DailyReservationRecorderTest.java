package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.PauseReason;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class DailyReservationRecorderTest {

    private static final Long OWNER = 7L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final long UNIT = 10L;
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Mock
    private PointAccountRepository accountRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private BudgetDailyPlanRepository planRepository;

    private DailyReservationRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new DailyReservationRecorder(accountRepository, ledgerRepository, planRepository);
    }

    private static CampaignInfo campaign(long id, CampaignStatus status, PauseReason reason, Long dailyBudget) {
        return new CampaignInfo(id, OWNER, 100L + id, status, 100, null, null, null, null, null, null, 0, null, 0,
                null, List.of(), null, null, CHANGED_AT, reason, dailyBudget);
    }

    private List<PointLedger> savedLedgers(int times) {
        ArgumentCaptor<PointLedger> captor = ArgumentCaptor.forClass(PointLedger.class);
        verify(ledgerRepository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    private List<BudgetDailyPlan> savedPlans(int times) {
        ArgumentCaptor<BudgetDailyPlan> captor = ArgumentCaptor.forClass(BudgetDailyPlan.class);
        verify(planRepository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void 잔액이_모자라면_캠페인_ID_순서로_앞에서부터_채우고_0이면_RESERVE없이_계획만_만든다() {
        List<CampaignInfo> campaigns = List.of(
                campaign(1L, CampaignStatus.ACTIVE, null, 10000L),
                campaign(2L, CampaignStatus.ACTIVE, null, 10000L),
                campaign(3L, CampaignStatus.PAUSED, PauseReason.OWNER, 3000L));
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L, 2L, 3L))).thenReturn(List.of());
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(15000L);

        recorder.reserveForOwner(OWNER, campaigns, DAY, UNIT);

        List<PointLedger> ledgers = savedLedgers(2);
        assertThat(ledgers).extracting(PointLedger::getCampaignId).containsExactly(1L, 2L);
        assertThat(ledgers).extracting(PointLedger::getAmount).containsExactly(-10000L, -5000L);
        assertThat(ledgers).extracting(PointLedger::getEntryType).containsOnly(EntryType.RESERVE);
        assertThat(ledgers).extracting(PointLedger::getBusinessDate).containsOnly(DAY);

        List<BudgetDailyPlan> plans = savedPlans(3);
        assertThat(plans).extracting(BudgetDailyPlan::getCampaignId).containsExactly(1L, 2L, 3L);
        assertThat(plans).extracting(BudgetDailyPlan::getDailyBudget).containsExactly(10000L, 5000L, 0L);
        assertThat(plans).extracting(BudgetDailyPlan::getUnitPrice).containsOnly(UNIT);
        assertThat(plans).extracting(BudgetDailyPlan::getOwnerId).containsOnly(OWNER);
        // PAUSED(OWNER) 는 중단으로 시작해야 판정 전 노출이 과금되지 않는다
        assertThat(plans).extracting(BudgetDailyPlan::isPaused).containsExactly(false, false, true);
        assertThat(plans).extracting(BudgetDailyPlan::getStatusChangedAt).containsOnly(CHANGED_AT);
    }

    @Test
    void 예약액은_하루_예산을_넘지_않는다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of());
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(50000L);

        recorder.reserveForOwner(OWNER, List.of(campaign(1L, CampaignStatus.ACTIVE, null, 10000L)), DAY, UNIT);

        assertThat(savedLedgers(1)).extracting(PointLedger::getAmount).containsExactly(-10000L);
        assertThat(savedPlans(1)).extracting(BudgetDailyPlan::getDailyBudget).containsExactly(10000L);
    }

    @Test
    void 계획이_이미_있는_캠페인은_건너뛰고_남은_잔액에서_이어서_배분한다() {
        List<CampaignInfo> campaigns = List.of(
                campaign(1L, CampaignStatus.ACTIVE, null, 10000L),
                campaign(2L, CampaignStatus.ACTIVE, null, 10000L));
        BudgetDailyPlan existing = BudgetDailyPlan.create(1L, OWNER, DAY, 10000L, UNIT, false, CHANGED_AT);
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L, 2L))).thenReturn(List.of(existing));
        // 1 의 예약이 이미 빠진 잔액이다
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(5000L);

        recorder.reserveForOwner(OWNER, campaigns, DAY, UNIT);

        assertThat(savedLedgers(1)).extracting(PointLedger::getCampaignId, PointLedger::getAmount)
                .containsExactly(tuple(2L, -5000L));
        assertThat(savedPlans(1)).extracting(BudgetDailyPlan::getCampaignId).containsExactly(2L);
    }

    @Test
    void 잔액을_읽기_전에_점주_계정을_잠근다() {
        when(accountRepository.lockByOwnerId(OWNER)).thenReturn(Optional.of(3L));
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of());
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(0L);

        recorder.reserveForOwner(OWNER, List.of(campaign(1L, CampaignStatus.ACTIVE, null, 1000L)), DAY, UNIT);

        InOrder order = inOrder(accountRepository, ledgerRepository);
        order.verify(accountRepository).lockByOwnerId(OWNER);
        order.verify(ledgerRepository).sumBalance(OWNER);
    }

    @Test
    void 잔액이_음수면_0으로_보고_예약없이_계획만_만든다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of());
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(-500L);

        recorder.reserveForOwner(OWNER, List.of(campaign(1L, CampaignStatus.ACTIVE, null, 1000L)), DAY, UNIT);

        verify(ledgerRepository, never()).save(any());
        assertThat(savedPlans(1)).extracting(BudgetDailyPlan::getDailyBudget).containsExactly(0L);
    }

    @Test
    void 하루_예산이_없는_서빙_대상이면_단계를_멈춘다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of());
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(1000L);

        assertThatThrownBy(() -> recorder.reserveForOwner(
                OWNER, List.of(campaign(1L, CampaignStatus.ACTIVE, null, null)), DAY, UNIT))
                .isInstanceOf(IllegalStateException.class);
        verify(planRepository, never()).save(any());
    }

    @Test
    void 보정_해제를_한_번에_저장한다() {
        List<PointLedger> releases = List.of(PointLedger.release(OWNER, 1L, DAY.minusDays(1), 2000L));

        recorder.release(releases);

        verify(ledgerRepository).saveAll(releases);
    }

    @Test
    void 쓰기는_각각_트랜잭션_하나다() throws Exception {
        assertThat(DailyReservationRecorder.class.getMethod("reserveForOwner", Long.class, List.class,
                LocalDate.class, long.class).isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(DailyReservationRecorder.class.getMethod("release", List.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
