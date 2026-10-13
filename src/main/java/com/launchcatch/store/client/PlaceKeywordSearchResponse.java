package com.launchcatch.store.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record PlaceKeywordSearchResponse(List<Document> documents) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(String id,
                    @JsonProperty("place_name") String placeName,
                    String phone,
                    @JsonProperty("road_address_name") String roadAddress,
                    String x,
                    String y) { }
}
