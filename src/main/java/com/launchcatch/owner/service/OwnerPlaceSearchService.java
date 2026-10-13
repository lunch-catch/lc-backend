package com.launchcatch.owner.service;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.owner.dto.PlaceSearchResponse;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import com.launchcatch.store.contract.StoreQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OwnerPlaceSearchService {
    private final OwnerRepository owners;
    private final StoreQueryService stores;

    public PlaceSearchResponse search(Long ownerId, String query) {
        OwnerStatus status = owners.findStatusById(ownerId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.LOGIN_REQUIRED));
        if (status != OwnerStatus.ONBOARDING) {
            throw new OwnerException(OwnerErrorCode.OWNER_STATE_NOT_ALLOWED);
        }
        var result = stores.searchKakaoPlaces(query);
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
