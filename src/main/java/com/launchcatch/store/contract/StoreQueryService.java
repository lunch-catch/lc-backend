package com.launchcatch.store.contract;

// 상호명으로 카카오 장소를 검색하며 검색어의 앞뒤 공백을 제거한다.
public interface StoreQueryService {
    PlaceSearchResult searchKakaoPlaces(String query);
}
