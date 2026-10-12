package com.launchcatch.member.dto;

import java.math.BigDecimal;

public record MemberLocationResponse(
        String locationNickname,
        String roadAddress,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
