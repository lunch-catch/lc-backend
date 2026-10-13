package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.PlaceSearchResult;
import com.launchcatch.store.contract.StoreQueryService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

class OwnerPlaceSearchServiceTest {
    private final OwnerRepository owners = Mockito.mock(OwnerRepository.class);
    private final StoreQueryService places = query -> {
        assertThat(query).isEqualTo("런치캐치");
        return new PlaceSearchResult(List.of(new PlaceSearchResult.Place(
                "123", "런치캐치 식당", null, "서울 강남구 테헤란로 1",
                new BigDecimal("37.5"), new BigDecimal("127.0"))));
    };

    @Test
    void DB_현재_상태가_온보딩이면_검색하고_장소를_반환한다() {
        Mockito.when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.ONBOARDING));
        var response = new OwnerPlaceSearchService(owners, places).search(7L, "런치캐치");

        assertThat(response.places()).singleElement().satisfies(place -> {
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
        Mockito.when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.ONBOARDING));
        var service = new OwnerPlaceSearchService(owners, query -> new PlaceSearchResult(List.of()));

        assertThat(service.search(7L, "없는가게").places()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OwnerStatus.class, names = {"ACTIVE", "SUSPENDED", "WITHDRAWN"})
    void 온보딩이_아니면_카카오를_호출하지_않고_거절한다(OwnerStatus status) {
        Mockito.when(owners.findStatusById(7L)).thenReturn(Optional.of(status));
        var service = new OwnerPlaceSearchService(owners, unexpectedSearch());

        assertThatThrownBy(() -> service.search(7L, "런치캐치"))
                .isInstanceOfSatisfying(OwnerException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(OwnerErrorCode.OWNER_STATE_NOT_ALLOWED));
    }

    @Test
    void 점주가_없으면_검색하지_않고_로그인을_요구한다() {
        Mockito.when(owners.findStatusById(7L)).thenReturn(Optional.empty());
        var service = new OwnerPlaceSearchService(owners, unexpectedSearch());

        assertThatThrownBy(() -> service.search(7L, "런치캐치"))
                .isInstanceOfSatisfying(AuthException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED));
    }

    @Test
    void 현재_상태_조회가_실패하면_검색하지_않고_실패를_전파한다() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        Mockito.when(owners.findStatusById(7L)).thenThrow(failure);
        var service = new OwnerPlaceSearchService(owners, unexpectedSearch());

        assertThatThrownBy(() -> service.search(7L, "런치캐치")).isSameAs(failure);
    }

    @Test
    void 카카오_장애를_빈_검색_결과로_바꾸지_않는다() {
        Mockito.when(owners.findStatusById(7L)).thenReturn(Optional.of(OwnerStatus.ONBOARDING));
        var failure = new IllegalStateException("search unavailable");
        var service = new OwnerPlaceSearchService(owners, query -> { throw failure; });

        assertThatThrownBy(() -> service.search(7L, "런치캐치")).isSameAs(failure);
    }

    private static StoreQueryService unexpectedSearch() {
        return query -> { throw new AssertionError("상태가 허용되지 않으면 검색하지 않아야 한다"); };
    }
}
