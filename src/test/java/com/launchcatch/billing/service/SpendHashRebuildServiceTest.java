package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.DailySpendHashRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SpendHashRebuildServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 14, 10, 5);

    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private DailySpendHashRepository hashRepository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private SpendHashRebuildService service;

    @BeforeEach
    void setUp() {
        service = new SpendHashRebuildService(planRepository, ledgerRepository, hashRepository, meterRegistry);
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
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.of(plan()));
        when(ledgerRepository.sumDeductForCampaignOn(7L, DAY)).thenReturn(250L);
        when(hashRepository.rebuildIfAbsent(DAY, 7L, 1000L, 250L, true, CHANGED_AT)).thenReturn(true);

        assertThat(service.rebuildIfAbsent(DAY, 7L)).isTrue();
        assertThat(rebuildCount()).isEqualTo(1.0);
    }

    @Test
    void 이미_있어_만들지_않았으면_지표를_올리지_않는다() {
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.of(plan()));
        when(ledgerRepository.sumDeductForCampaignOn(7L, DAY)).thenReturn(0L);
        when(hashRepository.rebuildIfAbsent(DAY, 7L, 1000L, 0L, true, CHANGED_AT)).thenReturn(false);

        assertThat(service.rebuildIfAbsent(DAY, 7L)).isFalse();
        assertThat(rebuildCount()).isZero();
    }

    @Test
    void 계획_행이_없으면_예외이고_아무것도_쓰지_않는다() {
        when(planRepository.findByCampaignIdAndBusinessDate(7L, DAY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rebuildIfAbsent(DAY, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=7");
        verifyNoInteractions(hashRepository, ledgerRepository);
    }
}
