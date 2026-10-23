package com.launchcatch.coupon.service;

import static com.launchcatch.coupon.service.CouponBatchFixture.DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.repository.CouponBatchRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.ops.contract.BatchAttemptGuard;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuQueryResult;
import com.launchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CouponPreparationServiceTest {
    private final CampaignQueryService campaigns = mock(CampaignQueryService.class);
    private final StoreQueryService stores = mock(StoreQueryService.class);
    private final CouponEventRepository events = mock(CouponEventRepository.class);
    private final CouponBatchRepository batches = mock(CouponBatchRepository.class);
    private final BatchAttemptGuard guard = mock(BatchAttemptGuard.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    @Test
    void 중단된_캠페인의_회차는_중단_상태로_준비한다() {
        var campaign = CouponBatchFixture.campaign(CampaignStatus.PAUSED);
        when(campaigns.findServableOn(DATE)).thenReturn(List.of(campaign));
        when(campaigns.findByIdForUpdate(91L)).thenReturn(Optional.of(campaign));
        when(stores.findAllById(List.of(10L))).thenReturn(List.of(new StoreInfo(10L, "식당", null, null, null)));
        when(events.saveAndFlush(any())).thenAnswer(call -> {
            CouponEvent event = call.getArgument(0);
            ReflectionTestUtils.setField(event, "id", 1L);
            return event;
        });

        assertThat(service().prepare(DATE, 1)).isEqualTo(1);

        ArgumentCaptor<CouponEvent> created = ArgumentCaptor.forClass(CouponEvent.class);
        verify(events).saveAndFlush(created.capture());
        assertThat(created.getValue().getStatus()).isEqualTo(CouponEventStatus.PAUSED);
        assertThat(created.getValue().getStoreName()).isEqualTo("식당");
    }

    @Test
    void 이미_생성한_회차는_원본_조회나_재고_생성을_반복하지_않는다() {
        var campaign = CouponBatchFixture.campaign(CampaignStatus.ACTIVE);
        when(campaigns.findServableOn(DATE)).thenReturn(List.of(campaign));
        when(campaigns.findByIdForUpdate(91L)).thenReturn(Optional.of(campaign));
        when(events.findByCampaignIdAndBusinessDate(91L, DATE)).thenReturn(Optional.of(CouponBatchFixture.event()));

        assertThat(service().prepare(DATE, 2)).isZero();
        verifyNoInteractions(stores, batches);
    }

    @Test
    void 대상_조회_후_종료된_캠페인은_생성하지_않는다() {
        when(campaigns.findServableOn(DATE)).thenReturn(List.of(CouponBatchFixture.campaign(CampaignStatus.ACTIVE)));
        when(campaigns.findByIdForUpdate(91L)).thenReturn(Optional.of(CouponBatchFixture.campaign(CampaignStatus.ENDED)));

        assertThat(service().prepare(DATE, 1)).isZero();
        verifyNoInteractions(stores, batches);
    }

    @Test
    void 실행권을_잃으면_생성_트랜잭션을_실패시킨다() {
        when(campaigns.findServableOn(DATE)).thenReturn(List.of(CouponBatchFixture.campaign(CampaignStatus.ACTIVE)));
        doThrow(new IllegalStateException("실행권 상실")).when(guard).assertCurrentAttempt("COUPON_PREPARE", DATE, 1);

        assertThatThrownBy(() -> service().prepare(DATE, 1)).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verifyNoInteractions(events, batches);
    }

    @Test
    void 생성_대상_캠페인이_없으면_빈_성공으로_대체하지_않는다() {
        when(campaigns.findServableOn(DATE)).thenReturn(List.of(CouponBatchFixture.campaign(CampaignStatus.ACTIVE)));
        when(campaigns.findByIdForUpdate(91L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().prepare(DATE, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("캠페인이 없다");
        verifyNoInteractions(batches);
    }

    @Test
    void 매장이_없으면_임의의_스냅샷을_만들지_않는다() {
        var campaign = CouponBatchFixture.campaign(CampaignStatus.ACTIVE);
        when(campaigns.findServableOn(DATE)).thenReturn(List.of(campaign));
        when(campaigns.findByIdForUpdate(91L)).thenReturn(Optional.of(campaign));
        when(stores.findAllById(List.of(10L))).thenReturn(List.of());

        assertThatThrownBy(() -> service().prepare(DATE, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("매장이 없다");
        verifyNoInteractions(batches);
    }

    @Test
    void 할인_메뉴가_없으면_현재_가격이나_기본값으로_대체하지_않는다() {
        var campaign = CouponBatchFixture.menuCampaign();
        when(campaigns.findServableOn(DATE)).thenReturn(List.of(campaign));
        when(campaigns.findByIdForUpdate(91L)).thenReturn(Optional.of(campaign));
        when(stores.findAllById(List.of(10L))).thenReturn(List.of(new StoreInfo(10L, "식당", null, null, null)));
        when(stores.findMenusByIdIn(List.of(100L))).thenReturn(new StoreMenuQueryResult(List.of(), List.of()));

        assertThatThrownBy(() -> service().prepare(DATE, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("메뉴가 없다");
        verifyNoInteractions(batches);
    }

    @Test
    void 할인_메뉴의_데이터_오류는_회차와_재고를_만들지_않고_롤백한다() {
        var campaign = CouponBatchFixture.menuCampaign();
        when(campaigns.findServableOn(DATE)).thenReturn(List.of(campaign));
        when(campaigns.findByIdForUpdate(91L)).thenReturn(Optional.of(campaign));
        when(stores.findAllById(List.of(10L))).thenReturn(List.of(new StoreInfo(10L, "식당", null, null, null)));
        when(stores.findMenusByIdIn(List.of(100L))).thenReturn(new StoreMenuQueryResult(List.of(), List.of(100L)));

        assertThatThrownBy(() -> service().prepare(DATE, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("검증된 이미지가 없다");

        verify(manager).rollback(any());
        verify(events, org.mockito.Mockito.never()).saveAndFlush(any());
        verifyNoInteractions(batches);
    }

    private CouponPreparationService service() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new CouponPreparationService(campaigns, stores, events, batches, guard,
                Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneId.of("Asia/Seoul")), manager);
    }
}
