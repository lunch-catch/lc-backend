package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.launchcatch.store.contract.OwnerStoreSnapshot.Image;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Menu;
import com.launchcatch.store.repository.StoreDetailData;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.OwnerStoreQueryRepository;
import com.launchcatch.store.repository.StoreDetailRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoreDetailServiceTest {
    private final StoreDetailRepository stores = mock(StoreDetailRepository.class);
    private final OwnerStoreQueryRepository details = mock(OwnerStoreQueryRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T15:00:00Z"), ZoneOffset.UTC);
    private final StoreDetailService service = new StoreDetailService(stores, details, "https://cdn.example.com", clock);

    private void registered() {
        when(stores.findFinalized(10L)).thenReturn(Optional.of(new StoreDetailData(10L, "식당", "KOREAN",
                "서울", "02-123-4567", new BigDecimal("37"), new BigDecimal("127"))));
        when(stores.findImages(10L, "LOGO")).thenReturn(List.of(new StoreDetailData.Image("stores/logo.png", null)));
    }

    @Test
    void 메뉴와_내부_이미지_URL을_반환한다() {
        registered();
        when(stores.findImages(10L, "INTERIOR")).thenReturn(List.of(new StoreDetailData.Image("interior.png", 1)));
        when(details.findMenus(10L)).thenReturn(List.of(new Menu(20L, "국수", 9000, null, 1,
                new Image("menu.png", null, null))));
        when(details.findBusinessHours(10L)).thenReturn(List.of(
                new com.launchcatch.store.contract.OwnerStoreSnapshot.BusinessHour("MONDAY", LocalTime.NOON,
                        LocalTime.of(20, 0), false, null, null, null)));
        var result = service.find(10L);
        assertThat(result.businessHours().getFirst().dayOfWeek()).isEqualTo("MONDAY");
        assertThat(result.interiorImages().getFirst().url()).isEqualTo("https://cdn.example.com/interior.png");
        assertThat(result.menus().getFirst().image().url()).isEqualTo("https://cdn.example.com/menu.png");
        assertThat(result.menus().getFirst().description()).isNull();
    }

    @Test
    void UTC_자정_전에도_서울의_오늘_캠페인을_조회한다() {
        registered();
        var campaign = new StoreDetailData.Campaign(30L,
                com.launchcatch.store.contract.StoreDiscountTargetType.ALL,
                com.launchcatch.store.contract.StoreDiscountType.AMOUNT, 1000, null, null, null,
                LocalTime.NOON, LocalTime.of(14, 0));
        when(stores.findActiveCampaign(10L, LocalDate.of(2026, 10, 20))).thenReturn(Optional.of(campaign));
        var result = service.find(10L).activeCampaign();
        assertThat(result.campaignId()).isEqualTo(campaign.campaignId());
        assertThat(result.usableStartTime()).isEqualTo(campaign.usableStartTime());
        assertThat(result.targetMenu()).isNull();
    }

    @Test
    void 미존재나_미등록_가게는_동일한_404다() {
        assertThatThrownBy(() -> service.find(10L)).isInstanceOfSatisfying(StoreException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
    }

    @Test
    void 확정된_가게에_대표_이미지가_없으면_데이터_오류다() {
        registered();
        when(stores.findImages(10L, "LOGO")).thenReturn(List.of());
        assertThatThrownBy(() -> service.find(10L)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("대표 이미지가 누락");
    }

    @Test
    void 검증된_메뉴_이미지가_없어도_메뉴는_유지한다() {
        registered();
        when(details.findMenus(10L)).thenReturn(List.of(new Menu(20L, "국수", 9000, null, 1, null)));
        var menu = service.find(10L).menus().getFirst();
        assertThat(menu.name()).isEqualTo("국수");
        assertThat(menu.image()).isNull();
    }

}
