package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.PlaceSearchResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.PlaceSearchResult;
import com.launchcatch.store.contract.StoreQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OwnerPlaceSearchService {
    private final OwnerRepository owners;
    private final StoreQueryService stores;
    private final OwnerPlaceSearchRateLimiter rateLimiter;

    public PlaceSearchResponse search(Long ownerId, String query) {
        OwnerStatus status = owners.findStatusById(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (status != OwnerStatus.ONBOARDING) {
            // TODO: 카카오 로컬 검색에서 허용하지 않는 점주 상태의 오류 코드를 정의하고 도메인 예외로 바꿔야 한다.
            throw new IllegalStateException("현재 점주 상태에서는 가게를 검색할 수 없습니다.");
        }
        rateLimiter.checkLimit(ownerId);
        PlaceSearchResult result = stores.searchKakaoPlaces(query);
        return new PlaceSearchResponse(result.places().stream()
                .map(place -> new PlaceSearchResponse.Place(
                        place.placeId(),
                        place.placeName(),
                        place.phone(),
                        place.roadAddress(),
                        place.latitude(),
                        place.longitude())
                )
                .toList());
    }
}
