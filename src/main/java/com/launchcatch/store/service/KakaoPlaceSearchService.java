package com.launchcatch.store.service;

import com.launchcatch.store.client.PlaceSearchClient;
import com.launchcatch.store.contract.PlaceSearchResult;
import com.launchcatch.store.contract.StoreQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KakaoPlaceSearchService implements StoreQueryService {
    private final PlaceSearchClient placeSearchClient;

    @Override
    public PlaceSearchResult searchKakaoPlaces(String query) {
        return placeSearchClient.search(query.strip());
    }
}
