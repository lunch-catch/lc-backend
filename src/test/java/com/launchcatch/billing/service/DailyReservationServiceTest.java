package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.CampaignSpend;
import com.launchcatch.billing.entity.BudgetDailyPlan;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.ops.contract.PlatformSettingQueryService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class DailyReservationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate PREV = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime CHANGED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);

    @Mock
    private CampaignQueryService campaignQuery;
    @Mock
    private PlatformSettingQueryService platformSetting;
    @Mock
    private BudgetDailyPlanRepository planRepository;
    @Mock
    private PointLedgerRepository ledgerRepository;
    @Mock
    private DailyReservationRecorder recorder;
    @Mock
    private SpendHashRebuildService hashRebuild;

    private DailyReservationService service;

    @BeforeEach
    void setUp() {
        service = new DailyReservationService(campaignQuery, platformSetting, planRepository, ledgerRepository,
                recorder, hashRebuild);
    }

    private static CampaignInfo campaign(long id, long ownerId) {
        return new CampaignInfo(id, ownerId, 100L + id, CampaignStatus.ACTIVE, 100, null, null, null, null, null,
                null, 0, null, 0, null, List.of(), null, null, CHANGED_AT, null, 1000L);
    }

    private static BudgetDailyPlan prevPlan(long campaignId, long ownerId, long reserved) {
        return BudgetDailyPlan.create(campaignId, ownerId, PREV, reserved, 10L, false, CHANGED_AT);
    }

    @Test
    void 단가가_0_이하이면_아무것도_읽거나_쓰지_않고_실패한다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(DAY)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(planRepository, ledgerRepository, campaignQuery, recorder, hashRebuild);
    }

    @Test
    void 단가_키가_없으면_운영의_예외로_실패한다() {
        when(platformSetting.getImpressionUnitPrice()).thenThrow(new IllegalStateException("IMPRESSION_UNIT_PRICE 없음"));

        assertThatThrownBy(() -> service.reserve(DAY)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(planRepository, recorder);
    }

    @Test
    void 전일_예약에서_쓰고_남은_몫만_보정_해제하고_이미_해제됐거나_남은_몫이_없으면_건너뛴다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(
                prevPlan(1L, 7L, 5000L),
                prevPlan(2L, 8L, 1000L),
                prevPlan(3L, 7L, 1000L),
                prevPlan(4L, 8L, 1000L)));
        when(ledgerRepository.findReleasedCampaignIds(Set.of(7L, 8L), PREV)).thenReturn(List.of(2L));
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(1L, 2L, 3L, 4L), PREV)).thenReturn(List.of(
                new CampaignSpend(1L, 3000L),
                new CampaignSpend(3L, 1000L),
                new CampaignSpend(4L, 1200L)));
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of());

        service.reserve(DAY);

        // 1 은 5000 - 3000 = 2000 을 돌려받는다. 2 는 이미 해제됐고 3 은 남은 몫이 없고 4 는 예약을 넘겨 썼다
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PointLedger>> captor = ArgumentCaptor.forClass(List.class);
        verify(recorder).release(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(release -> {
            assertThat(release.getEntryType()).isEqualTo(EntryType.RELEASE);
            assertThat(release.getCampaignId()).isEqualTo(1L);
            assertThat(release.getOwnerId()).isEqualTo(7L);
            assertThat(release.getBusinessDate()).isEqualTo(PREV);
            assertThat(release.getAmount()).isEqualTo(2000L);
        });
    }

    @Test
    void 해제할_몫이_없으면_기록하지_않는다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(prevPlan(1L, 7L, 0L)));
        when(ledgerRepository.findReleasedCampaignIds(Set.of(7L), PREV)).thenReturn(List.of());
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(1L), PREV)).thenReturn(List.of());
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of());

        assertThat(service.reserve(DAY)).isZero();
        verify(recorder, never()).release(anyList());
    }

    @Test
    void 전일_계획이_없으면_원장을_읽지_않는다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of());

        service.reserve(DAY);

        verifyNoInteractions(ledgerRepository);
        verify(recorder, never()).release(anyList());
    }

    @Test
    void 보정_해제가_실패하면_예약하지_않는다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(prevPlan(1L, 7L, 5000L)));
        when(ledgerRepository.findReleasedCampaignIds(Set.of(7L), PREV)).thenReturn(List.of());
        when(ledgerRepository.sumDeductForCampaignsOn(List.of(1L), PREV)).thenReturn(List.of());
        doThrow(new QueryTimeoutException("lock wait")).when(recorder).release(anyList());

        assertThatThrownBy(() -> service.reserve(DAY)).isInstanceOf(QueryTimeoutException.class);
        verifyNoInteractions(campaignQuery);
        verify(recorder, never()).reserveForOwner(any(), any(), any(), anyLong());
    }

    @Test
    void 점주별로_묶어_점주_ID와_캠페인_ID_순으로_예약하고_해시를_만든다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());
        CampaignInfo c5 = campaign(5L, 8L);
        CampaignInfo c3 = campaign(3L, 7L);
        CampaignInfo c1 = campaign(1L, 7L);
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of(c5, c3, c1));

        assertThat(service.reserve(DAY)).isEqualTo(3);

        InOrder order = inOrder(recorder, hashRebuild);
        order.verify(recorder).reserveForOwner(7L, List.of(c1, c3), DAY, 10L);
        order.verify(recorder).reserveForOwner(8L, List.of(c5), DAY, 10L);
        order.verify(hashRebuild).rebuildAllIfAbsent(DAY, List.of(5L, 3L, 1L));
    }

    @Test
    void 해시_만들기가_실패해도_단계는_성공한다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of(campaign(1L, 7L)));
        when(hashRebuild.rebuildAllIfAbsent(DAY, List.of(1L))).thenThrow(new QueryTimeoutException("valkey"));

        assertThat(service.reserve(DAY)).isEqualTo(1);
    }

    @Test
    void 해시는_500개씩_끊어_만든다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());
        List<CampaignInfo> campaigns = new ArrayList<>();
        for (long id = 1; id <= 501; id++) {
            campaigns.add(campaign(id, 7L));
        }
        when(campaignQuery.findServableOn(DAY)).thenReturn(campaigns);

        service.reserve(DAY);

        verify(recorder, times(1)).reserveForOwner(eq(7L), anyList(), eq(DAY), eq(10L));
        verify(hashRebuild, times(2)).rebuildAllIfAbsent(eq(DAY), anyList());
    }
}
