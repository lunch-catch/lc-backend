package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.PauseReason;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T15:00:00Z"), SEOUL);

    @Mock
    private PointAccountRepository accountRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private CampaignQueryService campaignQuery;
    @Mock
    private BudgetPlanStatusService planStatus;

    private DailyReservationRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new DailyReservationRecorder(accountRepository, ledgerRepository, planRepository, campaignQuery,
                planStatus, CLOCK);
    }

    private static CampaignInfo campaign(long id, CampaignStatus status, PauseReason reason, Long dailyBudget) {
        return campaign(id, status, reason, dailyBudget, CHANGED_AT);
    }

    private static CampaignInfo campaign(long id, CampaignStatus status, PauseReason reason, Long dailyBudget,
                                         LocalDateTime statusChangedAt) {
        return new CampaignInfo(id, OWNER, 100L + id, status, 100, null, null, null, null, null, null, 0, null, 0,
                null, List.of(), null, null, statusChangedAt, reason, dailyBudget);
    }

    // 무변경 upsert 로 넣은 RESERVE 를 넣은 순서대로 원장 값으로 되돌린다. JPA save 는 쓰지 않아야 한다
    private List<PointLedger> savedLedgers(int times) {
        ArgumentCaptor<Long> owners = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> campaignIds = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Long> amounts = ArgumentCaptor.forClass(Long.class);
        verify(ledgerRepository, times(times)).insertReserveIfAbsent(owners.capture(), campaignIds.capture(),
                dates.capture(), amounts.capture(), eq(LocalDateTime.now(CLOCK)));
        verify(ledgerRepository, never()).save(any());
        List<PointLedger> ledgers = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            ledgers.add(PointLedger.reserve(owners.getAllValues().get(i), campaignIds.getAllValues().get(i),
                    dates.getAllValues().get(i), -amounts.getAllValues().get(i)));
        }
        return ledgers;
    }

    // 무변경 upsert 로 넣은 계획을 넣은 순서대로 엔티티 값으로 되돌린다. JPA save 는 쓰지 않아야 한다
    private List<BudgetDailyPlan> savedPlans(int times) {
        ArgumentCaptor<Long> campaignIds = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> owners = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<Long> budgets = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> prices = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Boolean> paused = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<LocalDateTime> changedAts = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(planRepository, times(times)).insertIfAbsent(campaignIds.capture(), owners.capture(), dates.capture(),
                budgets.capture(), prices.capture(), paused.capture(), changedAts.capture(),
                eq(LocalDateTime.now(CLOCK)));
        verify(planRepository, never()).save(any());
        List<BudgetDailyPlan> plans = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            plans.add(BudgetDailyPlan.create(campaignIds.getAllValues().get(i), owners.getAllValues().get(i),
                    dates.getAllValues().get(i), budgets.getAllValues().get(i), prices.getAllValues().get(i),
                    paused.getAllValues().get(i), changedAts.getAllValues().get(i)));
        }
        return plans;
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
        // 계획은 캠페인 상태와 관계없이 중단으로 만들고, 커밋 뒤의 상태 확인이 ACTIVE 만 푼다
        assertThat(plans).extracting(BudgetDailyPlan::isPaused).containsOnly(true);
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
    void 계정_행을_없을_때만_만들고_잠근_뒤에_잔액을_읽는다() {
        when(accountRepository.lockByOwnerId(OWNER)).thenReturn(Optional.of(3L));
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of());
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(0L);

        recorder.reserveForOwner(OWNER, List.of(campaign(1L, CampaignStatus.ACTIVE, null, 1000L)), DAY, UNIT);

        // 충전 이력이 없는 점주도 잠글 행이 생겨야 겹친 실행이 같은 잠금에서 줄을 선다
        InOrder order = inOrder(accountRepository, planRepository, ledgerRepository);
        order.verify(accountRepository).createIfAbsent(OWNER, LocalDateTime.now(CLOCK));
        order.verify(accountRepository).lockByOwnerId(OWNER);
        order.verify(planRepository).findByBusinessDateAndCampaignIdIn(DAY, List.of(1L));
        order.verify(ledgerRepository).sumBalance(OWNER);
    }

    @Test
    void 잔액이_음수면_0으로_보고_예약없이_계획만_만든다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of());
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(-500L);

        recorder.reserveForOwner(OWNER, List.of(campaign(1L, CampaignStatus.ACTIVE, null, 1000L)), DAY, UNIT);

        verify(ledgerRepository, never()).insertReserveIfAbsent(any(), any(), any(), anyLong(), any());
        assertThat(savedPlans(1)).extracting(BudgetDailyPlan::getDailyBudget).containsExactly(0L);
    }

    @Test
    void 하루_예산이_없는_서빙_대상이면_단계를_멈춘다() {
        when(planRepository.findByBusinessDateAndCampaignIdIn(DAY, List.of(1L))).thenReturn(List.of());
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(1000L);

        assertThatThrownBy(() -> recorder.reserveForOwner(
                OWNER, List.of(campaign(1L, CampaignStatus.ACTIVE, null, null)), DAY, UNIT))
                .isInstanceOf(IllegalStateException.class);
        verify(planRepository, never()).insertIfAbsent(any(), any(), any(), anyLong(), anyLong(), anyBoolean(), any(),
                any());
    }

    @Test
    void 보정_해제는_점주_계정_행을_잠근_뒤_이미_있으면_그대로_두는_INSERT로_넣는다() {
        List<PointLedger> releases = List.of(
                PointLedger.release(OWNER, 1L, DAY.minusDays(1), 2000L),
                PointLedger.release(OWNER, 2L, DAY.minusDays(1), 500L));

        recorder.releaseForOwner(OWNER, releases);

        // RELEASE 도 잔액을 바꾸므로 환불 승인과 같은 계정 행 잠금에서 줄을 선다
        LocalDateTime now = LocalDateTime.now(CLOCK);
        InOrder order = inOrder(accountRepository, ledgerRepository);
        order.verify(accountRepository).createIfAbsent(OWNER, now);
        order.verify(accountRepository).lockByOwnerId(OWNER);
        order.verify(ledgerRepository).insertReleaseIfAbsent(OWNER, 1L, DAY.minusDays(1), 2000L, now);
        order.verify(ledgerRepository).insertReleaseIfAbsent(OWNER, 2L, DAY.minusDays(1), 500L, now);
        // 조회 뒤 saveAll 은 겹친 실행에서 UNIQUE 위반으로 단계를 실패시킨다
        verify(ledgerRepository, never()).saveAll(any());
    }

    @Test
    void 보정_해제에_RELEASE가_아니거나_다른_점주의_원장을_넘기면_잠그기_전에_거부한다() {
        List<PointLedger> reserves = List.of(PointLedger.reserve(OWNER, 1L, DAY, 2000L));
        List<PointLedger> others = List.of(PointLedger.release(8L, 2L, DAY.minusDays(1), 500L));

        assertThatThrownBy(() -> recorder.releaseForOwner(OWNER, reserves)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> recorder.releaseForOwner(OWNER, others)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accountRepository);
        verify(ledgerRepository, never()).insertReleaseIfAbsent(any(), any(), any(), anyLong(), any());
    }

    @Test
    void 상태_확인은_캠페인을_다시_읽어_ACTIVE만_중단을_풀고_나머지는_중단으로_맞춘다() {
        LocalDateTime pausedAt = CHANGED_AT.plusHours(1);
        when(campaignQuery.findAllById(List.of(1L, 2L, 3L, 4L))).thenReturn(List.of(
                // 예약이 읽은 뒤 계획을 만들기 전에 중단됐다
                campaign(1L, CampaignStatus.PAUSED, PauseReason.OWNER, 1000L, pausedAt),
                campaign(2L, CampaignStatus.ACTIVE, null, 1000L),
                campaign(3L, CampaignStatus.ENDED, null, 1000L, pausedAt)));
        when(planStatus.confirmStatus(1L, DAY, true, pausedAt)).thenReturn(true);
        when(planStatus.confirmStatus(2L, DAY, false, CHANGED_AT)).thenReturn(true);
        when(planStatus.confirmStatus(3L, DAY, true, pausedAt)).thenReturn(false);

        // 조회에서 빠진 4 는 건드리지 않아 중단으로 남는다
        assertThat(recorder.confirmStatus(List.of(1L, 2L, 3L, 4L), DAY)).isEqualTo(2);
        verify(planStatus, never()).confirmStatus(eq(4L), any(), anyBoolean(), any());
    }

    @Test
    void 쓰기는_각각_트랜잭션_하나다() throws Exception {
        assertThat(DailyReservationRecorder.class.getMethod("reserveForOwner", Long.class, List.class,
                LocalDate.class, long.class).isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(DailyReservationRecorder.class.getMethod("releaseForOwner", Long.class, List.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(DailyReservationRecorder.class.getMethod("confirmStatus", List.class, LocalDate.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
