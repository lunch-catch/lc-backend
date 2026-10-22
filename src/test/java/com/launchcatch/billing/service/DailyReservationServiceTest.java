package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        // 예약이 끝나면 그날 계획 전부를 읽어 상태를 확인한다. 전일 계획 조회만 스텁한 테스트에서 엄격 스텁이
        // 인자가 다른 호출로 실패하지 않게 기본은 빈 목록으로 둔다
        lenient().when(planRepository.findByBusinessDate(DAY)).thenReturn(List.of());
    }

    private static CampaignInfo campaign(long id, long ownerId) {
        return new CampaignInfo(id, ownerId, 100L + id, CampaignStatus.ACTIVE, 100, null, null, null, null, null,
                null, 0, null, 0, null, List.of(), null, null, CHANGED_AT, null, 1000L);
    }

    private static BudgetDailyPlan prevPlan(long campaignId, long ownerId, long reserved) {
        return BudgetDailyPlan.create(campaignId, ownerId, PREV, reserved, 10L, false, CHANGED_AT);
    }

    private static BudgetDailyPlan todayPlan(long campaignId, long ownerId) {
        return BudgetDailyPlan.create(campaignId, ownerId, DAY, 1000L, 10L, true, CHANGED_AT);
    }

    @Test
    void 단가가_0_이하이면_아무것도_읽거나_쓰지_않고_실패한다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(DAY)).isInstanceOf(IllegalStateException.class);
        verify(planRepository).findDistinctUnitPricesOn(DAY);
        verifyNoMoreInteractions(planRepository);
        verifyNoInteractions(ledgerRepository, campaignQuery, recorder, hashRebuild);
    }

    @Test
    void 단가_키가_없으면_운영의_예외로_실패한다() {
        when(platformSetting.getImpressionUnitPrice()).thenThrow(new IllegalStateException("IMPRESSION_UNIT_PRICE 없음"));

        assertThatThrownBy(() -> service.reserve(DAY)).isInstanceOf(IllegalStateException.class);
        verify(planRepository, never()).findByBusinessDate(any());
        verifyNoInteractions(recorder);
    }

    @Test
    void 그날_계획이_이미_있으면_운영_설정이_아니라_계획에_저장된_단가로_이어서_예약한다() {
        // 앞선 실행이 단가 10 으로 일부 점주를 예약하고 실패한 뒤 운영 설정이 20 으로 바뀐 경우다
        when(planRepository.findDistinctUnitPricesOn(DAY)).thenReturn(List.of(10L));
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());
        CampaignInfo c1 = campaign(1L, 7L);
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of(c1));

        service.reserve(DAY);

        verify(recorder).reserveForOwner(7L, List.of(c1), DAY, 10L);
        verifyNoInteractions(platformSetting);
    }

    @Test
    void 그날_계획에_단가가_여럿이면_이미_어긋난_것이라_아무것도_쓰지_않고_실패한다() {
        when(planRepository.findDistinctUnitPricesOn(DAY)).thenReturn(List.of(10L, 20L));

        assertThatThrownBy(() -> service.reserve(DAY)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(platformSetting, ledgerRepository, campaignQuery, recorder, hashRebuild);
    }

    @Test
    void 전일_예약에서_쓰고_남은_몫만_보정_해제하고_이미_해제됐거나_남은_몫이_없으면_건너뛴다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(
                prevPlan(1L, 7L, 5000L),
                prevPlan(2L, 8L, 1000L),
                prevPlan(3L, 7L, 1000L),
                prevPlan(4L, 8L, 1000L)));
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of());

        service.reserve(DAY);

        // 1 은 5000 - 3000 = 2000 을 돌려받는다. 2 는 이미 해제됐고 3 은 남은 몫이 없고 4 는 예약을 넘겨 썼다
        verify(recorder).releaseForOwner(7L, PREV, List.of(1L, 3L));
        verify(recorder).releaseForOwner(8L, PREV, List.of(2L, 4L));
    }

    @Test
    void 해제할_몫이_없으면_기록하지_않는다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(prevPlan(1L, 7L, 0L)));
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of());

        assertThat(service.reserve(DAY)).isZero();
        verify(recorder).releaseForOwner(7L, PREV, List.of(1L));
    }

    @Test
    void 보정_해제는_점주_ID_순으로_점주마다_따로_기록한다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(
                prevPlan(3L, 9L, 1000L), prevPlan(1L, 7L, 1000L), prevPlan(2L, 7L, 500L)));
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of());

        service.reserve(DAY);

        // 점주마다 계정 행을 잠근 짧은 트랜잭션이라 한 점주의 해제가 다른 점주의 충전과 환불을 붙잡지 않는다
        InOrder order = inOrder(recorder);
        order.verify(recorder).releaseForOwner(7L, PREV, List.of(1L, 2L));
        order.verify(recorder).releaseForOwner(9L, PREV, List.of(3L));
    }

    @Test
    void release는_그날_계획의_남은_몫을_해제한다() {
        // 13:30 해제(구현 순서 11번)가 00:00 보정과 같은 계산을 그날 영업일로 직접 부르는 자리다
        when(planRepository.findByBusinessDate(DAY))
                .thenReturn(List.of(BudgetDailyPlan.create(1L, 7L, DAY, 5000L, 10L, false, CHANGED_AT)));

        service.release(DAY);

        verify(recorder).releaseForOwner(7L, DAY, List.of(1L));
    }

    @Test
    void 전일_계획이_없으면_원장을_읽지_않는다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of());

        service.reserve(DAY);

        verifyNoInteractions(ledgerRepository);
        verify(recorder, never()).releaseForOwner(any(), anyList());
    }

    @Test
    void 보정_해제가_실패하면_예약하지_않는다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of(prevPlan(1L, 7L, 5000L)));
        doThrow(new QueryTimeoutException("lock wait")).when(recorder)
                .releaseForOwner(eq(7L), eq(PREV), anyList());

        assertThatThrownBy(() -> service.reserve(DAY)).isInstanceOf(QueryTimeoutException.class);
        verifyNoInteractions(campaignQuery);
        verify(recorder, never()).reserveForOwner(any(), any(), any(), anyLong());
    }

    @Test
    void 점주별로_묶어_점주_ID와_캠페인_ID_순으로_예약하고_그날_계획_전부의_상태를_확인한_다음_해시를_만든다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());
        CampaignInfo c5 = campaign(5L, 8L);
        CampaignInfo c3 = campaign(3L, 7L);
        CampaignInfo c1 = campaign(1L, 7L);
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of(c5, c3, c1));
        // 9 는 앞선 실행이 계획을 만든 뒤 예약 대상에서 빠진 캠페인이다(관리자 중단, 종료)
        when(planRepository.findByBusinessDate(DAY)).thenReturn(List.of(
                todayPlan(5L, 8L), todayPlan(9L, 7L), todayPlan(1L, 7L), todayPlan(3L, 7L)));

        assertThat(service.reserve(DAY)).isEqualTo(3);

        // 확인은 모든 예약이 커밋된 뒤 이번 실행의 대상이 아니라 그날 계획 전부로 하고, 해시는 확인된 계획으로 만든다
        InOrder order = inOrder(recorder, hashRebuild);
        order.verify(recorder).reserveForOwner(7L, List.of(c1, c3), DAY, 10L);
        order.verify(recorder).reserveForOwner(8L, List.of(c5), DAY, 10L);
        order.verify(recorder).confirmStatus(List.of(1L, 3L, 5L, 9L), DAY);
        order.verify(hashRebuild).rebuildAllIfAbsent(DAY, List.of(5L, 3L, 1L));
    }

    @Test
    void 상태_확인이_실패하면_해시를_만들지_않고_단계를_실패시킨다() {
        when(platformSetting.getImpressionUnitPrice()).thenReturn(10);
        when(planRepository.findByBusinessDate(PREV)).thenReturn(List.of());
        when(campaignQuery.findServableOn(DAY)).thenReturn(List.of(campaign(1L, 7L)));
        when(planRepository.findByBusinessDate(DAY)).thenReturn(List.of(todayPlan(1L, 7L)));
        doThrow(new QueryTimeoutException("lock wait")).when(recorder).confirmStatus(List.of(1L), DAY);

        // 확인되지 않은 계획은 중단으로 남아 과금되지 않고, 재실행이 다시 확인한다
        assertThatThrownBy(() -> service.reserve(DAY)).isInstanceOf(QueryTimeoutException.class);
        verifyNoInteractions(hashRebuild);
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
