package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.cache.DailySpend;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.exception.BudgetCounterUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class BudgetQueryServiceImplTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 14, 11, 30);
    // 10~12시에만 34·33·33 을 둔 24개 가중치다. 예약액 10,000 이면 11:30 의 계획 합이 5,050 이다
    private static final BillingScheduleProperties SCHEDULE = new BillingScheduleProperties(lunchOnlyWeights());

    private static List<Integer> lunchOnlyWeights() {
        List<Integer> weights = new ArrayList<>(Collections.nCopies(24, 0));
        weights.set(10, 34);
        weights.set(11, 33);
        weights.set(12, 33);
        return weights;
    }

    @Mock
    private DailyPlanSnapshotService snapshotService;
    @Mock
    private HourlyTargetService targetService;
    @Mock
    private DailySpendCounter spendCounter;
    @Mock
    private SpendHashRebuildService rebuildService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private BudgetQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BudgetQueryServiceImpl(snapshotService, targetService, spendCounter, rebuildService,
                SCHEDULE, meterRegistry);
    }

    // 요청 순서를 지킨 계획 스냅샷 맵이다. 캠페인 ID n 의 계획 ID 는 100 + n, 점주는 3 이다
    private static Map<Long, PlanSnapshot> plans(long... campaignIds) {
        Map<Long, PlanSnapshot> plans = new LinkedHashMap<>();
        for (long campaignId : campaignIds) {
            plans.put(campaignId, new PlanSnapshot(100 + campaignId, 3L, 10L * campaignId));
        }
        return plans;
    }

    private double fallbackCount() {
        return meterRegistry.counter(BudgetQueryServiceImpl.FALLBACK_METRIC).count();
    }

    @Test
    void 여러_캠페인을_한_번의_조회로_계산하고_요청_순서를_지킨다() {
        when(snapshotService.findAll(DAY, List.of(2L, 1L))).thenReturn(plans(2L, 1L));
        when(spendCounter.readAll(DAY, List.of(2L, 1L)))
                .thenReturn(Map.of(1L, new DailySpend(10_000L, 6_060L), 2L, new DailySpend(10_000L, 0L)));
        when(targetService.findAll(List.of(102L, 101L)))
                .thenReturn(Map.of(101L, Map.of(10, 3400L, 11, 3300L, 12, 3300L)));

        List<BudgetProgress> result = service.getProgress(List.of(2L, 1L), DAY, AT);

        assertThat(result).extracting(BudgetProgress::campaignId).containsExactly(2L, 1L);
        BudgetProgress first = result.get(1);
        assertThat(first.reservedAmount()).isEqualTo(10_000L);
        assertThat(first.unitPrice()).isEqualTo(10L);
        assertThat(first.todaySpend()).isEqualTo(6_060L);
        assertThat(first.plannedAmountUntil()).isEqualTo(5_050L);
        assertThat(first.progressRate()).isEqualTo(1.2);
        verify(snapshotService, times(1)).findAll(any(), any());
        verify(spendCounter, times(1)).readAll(any(), any());
        verify(targetService, times(1)).findAll(any());
        verify(rebuildService, never()).rebuildIfAbsent(any(), any());
    }

    @Test
    void 목표_행이_없으면_가중치로_같은_값을_계산하고_지표를_올린다() {
        when(snapshotService.findAll(DAY, List.of(2L))).thenReturn(plans(2L));
        when(spendCounter.readAll(DAY, List.of(2L))).thenReturn(Map.of(2L, new DailySpend(10_000L, 0L)));
        when(targetService.findAll(List.of(102L))).thenReturn(Map.of());

        BudgetProgress progress = service.getProgress(List.of(2L), DAY, AT).get(0);

        assertThat(progress.plannedAmountUntil()).isEqualTo(5_050L);
        assertThat(progress.progressRate()).isZero();
        assertThat(fallbackCount()).isEqualTo(1.0);
    }

    @Test
    void 그날_계획이_없는_캠페인은_결과에서_빠지고_모두_없으면_해시를_읽지_않는다() {
        when(snapshotService.findAll(DAY, List.of(9L))).thenReturn(Map.of());

        assertThat(service.getProgress(List.of(9L), DAY, AT)).isEmpty();
        verifyNoInteractions(spendCounter, targetService, rebuildService);
    }

    @Test
    void 후보가_상한이면_처리하고_넘으면_아무것도_읽지_않고_거부한다() {
        List<Long> atLimit = java.util.stream.LongStream.rangeClosed(1, BudgetQueryService.MAX_CAMPAIGN_IDS)
                .boxed().toList();
        when(snapshotService.findAll(DAY, atLimit)).thenReturn(Map.of());
        assertThat(service.getProgress(atLimit, DAY, AT)).isEmpty();

        List<Long> overLimit = java.util.stream.LongStream.rangeClosed(1, BudgetQueryService.MAX_CAMPAIGN_IDS + 1L)
                .boxed().toList();
        assertThatThrownBy(() -> service.getProgress(overLimit, DAY, AT))
                .isInstanceOf(IllegalArgumentException.class);
        verify(snapshotService, times(1)).findAll(any(), any());
        verifyNoInteractions(spendCounter, targetService, rebuildService);
    }

    @Test
    void 해시가_없는_캠페인만_재구성하고_그_캠페인만_다시_읽는다() {
        when(snapshotService.findAll(DAY, List.of(1L, 2L))).thenReturn(plans(1L, 2L));
        when(spendCounter.readAll(DAY, List.of(1L, 2L))).thenReturn(Map.of(1L, new DailySpend(10_000L, 0L)));
        when(spendCounter.readAll(DAY, List.of(2L))).thenReturn(Map.of(2L, new DailySpend(5_000L, 100L)));
        when(targetService.findAll(List.of(101L, 102L))).thenReturn(Map.of());

        List<BudgetProgress> result = service.getProgress(List.of(1L, 2L), DAY, AT);

        assertThat(result).extracting(BudgetProgress::reservedAmount).containsExactly(10_000L, 5_000L);
        verify(rebuildService).rebuildAllIfAbsent(DAY, List.of(2L));
        verify(rebuildService, never()).rebuildIfAbsent(any(), any());
    }

    @Test
    void 재구성한_뒤에도_해시가_없으면_예외다() {
        when(snapshotService.findAll(DAY, List.of(1L))).thenReturn(plans(1L));
        when(spendCounter.readAll(DAY, List.of(1L))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.getProgress(List.of(1L), DAY, AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("campaignId=1");
        verify(rebuildService).rebuildAllIfAbsent(DAY, List.of(1L));
    }

    @Test
    void Valkey_읽기_장애는_대체값_없이_전용_예외로_바뀐다() {
        when(snapshotService.findAll(DAY, List.of(1L))).thenReturn(plans(1L));
        QueryTimeoutException cause = new QueryTimeoutException("timeout");
        when(spendCounter.readAll(DAY, List.of(1L))).thenThrow(cause);

        assertThatThrownBy(() -> service.getProgress(List.of(1L), DAY, AT))
                .isInstanceOf(BudgetCounterUnavailableException.class)
                .hasCause(cause);
    }

    @Test
    void 재구성_중_Valkey_장애도_대체값_없이_전용_예외로_바뀐다() {
        when(snapshotService.findAll(DAY, List.of(1L))).thenReturn(plans(1L));
        when(spendCounter.readAll(DAY, List.of(1L))).thenReturn(Map.of());
        QueryTimeoutException cause = new QueryTimeoutException("timeout");
        doThrow(cause).when(rebuildService).rebuildAllIfAbsent(DAY, List.of(1L));

        assertThatThrownBy(() -> service.getProgress(List.of(1L), DAY, AT))
                .isInstanceOf(BudgetCounterUnavailableException.class)
                .hasCause(cause);
    }
}
