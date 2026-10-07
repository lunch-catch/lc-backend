package com.launchcatch.member.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = false)
public record MemberLocationRequest(
        @Size(max = 50) String locationNickname,
        @Size(max = 255) String roadAddress,
        @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude
) {
    @AssertTrue(message = "latitude and longitude must be provided together")
    public boolean hasCoordinatePair() {
        return latitude != null && longitude != null;
    }
}
