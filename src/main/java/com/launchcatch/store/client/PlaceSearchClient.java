package com.launchcatch.store.client;

import com.launchcatch.store.contract.PlaceSearchResult;

public interface PlaceSearchClient {
    PlaceSearchResult search(String query);
}
