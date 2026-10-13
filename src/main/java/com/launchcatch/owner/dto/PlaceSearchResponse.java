package com.launchcatch.owner.dto;

import java.math.BigDecimal;
import java.util.List;

public record PlaceSearchResponse(List<Place> places) {
    public PlaceSearchResponse {
        places = List.copyOf(places);
    }

    public record Place(String placeId, String placeName, String phone, String roadAddress,
                        BigDecimal latitude, BigDecimal longitude) { }
}
