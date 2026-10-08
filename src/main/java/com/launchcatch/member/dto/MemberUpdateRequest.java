package com.launchcatch.member.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record MemberUpdateRequest(
        @Size(min = 1, max = 20) @Pattern(regexp = ".*\\S.*") String nickname,
        Boolean notificationOptIn,
        Boolean locationOptIn
) {

    public boolean hasUpdate() {
        return nickname != null || notificationOptIn != null || locationOptIn != null;
    }
}
