package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lunchcatch.store.contract.OwnerStoreSnapshot.Image;
import com.lunchcatch.store.contract.OwnerStoreSnapshot.Menu;
import com.lunchcatch.store.contract.StoreDiscountTargetType;
import com.lunchcatch.store.contract.StoreDiscountType;
import com.lunchcatch.store.exception.StoreErrorCode;
import com.lunchcatch.store.exception.StoreException;
import com.lunchcatch.store.repository.OwnerStoreQueryRepository;
import com.lunchcatch.store.repository.StoreDetailData;
import com.lunchcatch.store.repository.StoreDetailRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

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
                new com.lunchcatch.store.contract.OwnerStoreSnapshot.BusinessHour("MONDAY", LocalTime.NOON,
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
                StoreDiscountTargetType.ALL,
                StoreDiscountType.AMOUNT, 1000, null, null, null,
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
    void 확정된_가게에_대표_이미지가_없어도_null로_조회된다() {
        registered();
        when(stores.findImages(10L, "LOGO")).thenReturn(List.of());
        var withoutCdn = new StoreDetailService(stores, details, "", clock);
        assertThat(withoutCdn.find(10L).logoImage()).isNull();
    }

    @Test
    void 검증된_메뉴_이미지가_없어도_메뉴는_유지한다() {
        registered();
        when(details.findMenus(10L)).thenReturn(List.of(new Menu(20L, "국수", 9000, null, 1, null)));
        var menu = service.find(10L).menus().getFirst();
        assertThat(menu.name()).isEqualTo("국수");
        assertThat(menu.image()).isNull();
    }


    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void 이미지_키가_비어도_가게와_메뉴와_정상_내부_이미지를_유지한다(String key) {
        registered();
        when(stores.findImages(10L, "LOGO")).thenReturn(List.of(new StoreDetailData.Image(key, null)));
        when(stores.findImages(10L, "INTERIOR")).thenReturn(List.of(
                new StoreDetailData.Image(key, 1), new StoreDetailData.Image("interior.png", 2)));
        when(details.findMenus(10L)).thenReturn(List.of(new Menu(20L, "국수", 9000, null, 1,
                new Image(key, null, null))));

        var result = service.find(10L);

        assertThat(result.storeId()).isEqualTo(10L);
        assertThat(result.logoImage()).isNull();
        assertThat(result.menus()).singleElement().satisfies(menu -> {
            assertThat(menu.menuId()).isEqualTo(20L);
            assertThat(menu.name()).isEqualTo("국수");
            assertThat(menu.price()).isEqualTo(9000L);
            assertThat(menu.image()).isNull();
        });
        assertThat(result.interiorImages()).singleElement().satisfies(image -> {
            assertThat(image.sortOrder()).isEqualTo(2);
            assertThat(image.url()).isEqualTo("https://cdn.example.com/interior.png");
        });
    }

    @Test
    void MENU_대상_메뉴가_없으면_캠페인만_제외하고_가게를_반환한다() {
        registered();
        when(stores.findActiveCampaign(10L, LocalDate.of(2026, 10, 20))).thenReturn(Optional.of(
                new StoreDetailData.Campaign(30L, StoreDiscountTargetType.MENU,
                        StoreDiscountType.AMOUNT, 1000, null, null, null,
                        LocalTime.NOON, LocalTime.of(14, 0))));

        var result = service.find(10L);

        assertThat(result.storeId()).isEqualTo(10L);
        assertThat(result.name()).isEqualTo("식당");
        assertThat(result.logoImage().url()).isEqualTo("https://cdn.example.com/stores/logo.png");
        assertThat(result.activeCampaign()).isNull();
    }

    @Test
    void 이미지_데이터_오류가_있어도_정상_이미지의_CDN_설정_오류는_전파한다() {
        registered();
        when(stores.findImages(10L, "LOGO")).thenReturn(List.of(new StoreDetailData.Image(" ", null)));
        when(stores.findImages(10L, "INTERIOR")).thenReturn(List.of(new StoreDetailData.Image("ok.png", 1)));
        var withoutCdn = new StoreDetailService(stores, details, "", clock);

        assertThatThrownBy(() -> withoutCdn.find(10L)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cdn.base-url");
    }

    @Test
    void 상세_DB_장애는_가게_없음으로_바꾸지_않는다() {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(stores.findFinalized(10L)).thenThrow(failure);

        assertThatThrownBy(() -> service.find(10L)).isSameAs(failure);
    }

    @Test
    void 정상_MENU_캠페인은_이미지_누락과_관계없이_유지한다() {
        registered();
        when(stores.findImages(10L, "LOGO")).thenReturn(List.of(new StoreDetailData.Image(" ", null)));
        when(stores.findActiveCampaign(10L, LocalDate.of(2026, 10, 20))).thenReturn(Optional.of(
                new StoreDetailData.Campaign(30L, StoreDiscountTargetType.MENU, StoreDiscountType.AMOUNT,
                        1000, 20L, "국수", 9000L, LocalTime.NOON, LocalTime.of(14, 0))));

        var result = service.find(10L);

        assertThat(result.logoImage()).isNull();
        assertThat(result.activeCampaign().campaignId()).isEqualTo(30L);
        assertThat(result.activeCampaign().targetMenu().menuId()).isEqualTo(20L);
        assertThat(result.activeCampaign().targetMenu().name()).isEqualTo("국수");
        assertThat(result.activeCampaign().targetMenu().price()).isEqualTo(9000L);
    }

}
