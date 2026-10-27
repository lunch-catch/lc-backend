package com.lunchcatch.owner.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record OwnerTermsAgreementRequest() {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("지원하지 않는 약관 동의 요청 항목입니다.");
    }
}