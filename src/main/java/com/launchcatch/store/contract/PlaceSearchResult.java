package com.launchcatch.store.contract;

import java.math.BigDecimal;
import java.util.List;

public record PlaceSearchResult(List<Place> places) {
    public PlaceSearchResult {
        places = List.copyOf(places);
    }

    public record Place(String placeId, String placeName, String phone, String roadAddress,
                        BigDecimal latitude, BigDecimal longitude) { }
}
