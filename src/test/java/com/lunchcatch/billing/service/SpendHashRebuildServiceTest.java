package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.CampaignSpend;
import com.lunchcatch.billing.entity.BudgetDailyPlan;
import com.lunchcatch.billing.repository.BudgetDailyPlanRepository;
import com.lunchcatch.billing.cache.DailySpendCounter;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class SpendHashRebuildServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 14, 10, 5);

    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private DailySpendCounter spendCounter;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private SpendHashRebuildService service;

    @BeforeEach
    void setUp() {
        service = new SpendHashRebuildService(planRepository, ledgerRepository, spendCounter, meterRegistry);
    }

    private static BudgetDailyPlan planOf(long campaignId, long dailyBudget) {
        BudgetDailyPlan plan = plan();
        ReflectionTestUtils.setField(plan, "campaignId", campaignId);
        ReflectionTestUtils.setField(plan, "dailyBudget", dailyBudget);
        return plan;
    }

    private static BudgetDailyPlan plan() {
        BudgetDailyPlan plan = BeanUtils.instantiateClass(BudgetDailyPlan.class);
        ReflectionTestUtils.setField(plan, "dailyBudget", 1000L);
        ReflectionTestUtils.setField(plan, "paused", true);
        ReflectionTestUtils.setField(plan, "statusChangedAt", CHANGED_AT);
        return plan;
    }

    private double rebuildCount() {
        return meterRegistry.counter(SpendHashRebuildService.REBUILD_METRIC).count();
    }

    @Test
    void 계획과_원장_합으로_해시를_만들고_지표를_올린다() {
        when(planRepository.findForShare(7L, DAY)).thenReturn(Optional.of(plan()));
        when(ledgerRepository.sumDeductForCampaignOn(7L, DAY)).thenReturn(250L);
        when(spendCounter.rebuildIfAbsent(DAY, 7L, 1000L, 250L, true, CHANGED_AT)).thenReturn(true);

        assertThat(service.rebuildIfAbsent(DAY, 7L)).isTrue();
        assertThat(rebuildCount()).isEqualTo(1.0);
        // 잠금 없는 읽기로 계획을 읽으면 그 뒤 커밋된 중단이 해시에 빠질 수 있다
        verify(planRepository, never()).findByCampaignIdAndBusinessDate(any(), any());
    }

    @Test
    void 재구성은_잠금이_트랜잭션_끝까지_가도록_트랜잭션_안에서만_돈다() throws Exception {
        assertThat(SpendHashRebuildService.class.getMethod("rebuildIfAbsent", LocalDate.class, Long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(SpendHashRebuildService.class.getMethod("rebuildAllIfAbsent", LocalDate.class, java.util.Collection.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void 이미_있어_만들지_않았으면_지표를_올리지_않는다() {
        when(planRepository.findForShare(7L, DAY)).thenReturn(Optional.of(plan()));
        when(ledgerRepository.sumDeductForCampaignOn(7L, DAY)).thenReturn(0L);
        when(spendCounter.rebuildIfAbsent(DAY, 7L, 1000L, 0L, true, CHANGED_AT)).thenReturn(false);

        assertThat(service.rebuildIfAbsent(DAY, 7L)).isFalse();
        assertThat(rebuildCount()).isZero();
    }

    @Test
    void 여러_캠페인은_계획과_원장_합을_한_번씩만_읽고_소진이_없는_캠페인은_0으로_만든다() {
        when(planRepository.findAllForShare(DAY, List.of(7L, 8L)))
                .thenReturn(List.of(planOf(7L, 1000L), planOf(8L, 2000L)));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(7L, 8L), DAY))
                .thenReturn(List.of(new CampaignSpend(7L, 250L)));
        when(spendCounter.rebuildIfAbsent(DAY, 7L, 1000L, 250L, true, CHANGED_AT)).thenReturn(true);
        when(spendCounter.rebuildIfAbsent(DAY, 8L, 2000L, 0L, true, CHANGED_AT)).thenReturn(false);

        assertThat(service.rebuildAllIfAbsent(DAY, List.of(7L, 8L))).isEqualTo(1);
        assertThat(rebuildCount()).isEqualTo(1.0);
        verify(planRepository, times(1)).findAllForShare(DAY, List.of(7L, 8L));
        verify(planRepository, never()).findByBusinessDateAndCampaignIdIn(any(), any());
        verify(ledgerRepository, times(1)).sumDeductForCampaignsOn(List.of(7L, 8L), DAY);
    }

    @Test
    void 여러_캠페인_중_계획_행이_없는_것이_있으면_예외이고_하나도_쓰지_않는다() {
        when(planRepository.findAllForShare(DAY, List.of(7L, 8L)))
                .thenReturn(List.of(planOf(7L, 1000L)));

        assertThatThrownBy(() -> service.rebuildAllIfAbsent(DAY, List.of(7L, 8L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=8");
        verifyNoInteractions(spendCounter, ledgerRepository);
    }

    @Test
    void 계획_행이_없으면_예외이고_아무것도_쓰지_않는다() {
        when(planRepository.findForShare(7L, DAY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rebuildIfAbsent(DAY, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=7");
        verifyNoInteractions(spendCounter, ledgerRepository);
    }
}
