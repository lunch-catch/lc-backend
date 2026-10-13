package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.store.client.PlaceSearchClient;
import com.launchcatch.store.contract.PlaceSearchResult;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class KakaoPlaceSearchServiceTest {
    @Test
    void 점주_정보_없이_검색어의_공백을_제거하고_공개_검색_결과를_반환한다() {
        PlaceSearchClient client = query -> {
            assertThat(query).isEqualTo("런치캐치");
            return new PlaceSearchResult(List.of(new PlaceSearchResult.Place(
                    "123", "런치캐치 식당", null, "서울 강남구 테헤란로 1",
                    new BigDecimal("37.5"), new BigDecimal("127.0"))));
        };

        var result = new KakaoPlaceSearchService(client).searchKakaoPlaces("  런치캐치  ");

        assertThat(result.places()).singleElement().satisfies(place -> {
            assertThat(place.placeId()).isEqualTo("123");
            assertThat(place.placeName()).isEqualTo("런치캐치 식당");
            assertThat(place.phone()).isNull();
            assertThat(place.roadAddress()).isEqualTo("서울 강남구 테헤란로 1");
            assertThat(place.latitude()).isEqualByComparingTo("37.5");
            assertThat(place.longitude()).isEqualByComparingTo("127.0");
        });
    }

    @Test
    void 검색_결과가_없으면_빈_목록을_반환한다() {
        var service = new KakaoPlaceSearchService(query -> new PlaceSearchResult(List.of()));

        assertThat(service.searchKakaoPlaces("없는가게").places()).isEmpty();
    }

    @Test
    void 카카오_장애를_빈_결과로_바꾸지_않는다() {
        var failure = new StoreException(StoreErrorCode.KAKAO_PLACE_SEARCH_UNAVAILABLE);
        var service = new KakaoPlaceSearchService(query -> { throw failure; });

        assertThatThrownBy(() -> service.searchKakaoPlaces("런치캐치")).isSameAs(failure);
    }
}
