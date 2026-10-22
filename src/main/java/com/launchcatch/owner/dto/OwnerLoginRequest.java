package com.launchcatch.owner.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OwnerLoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 10, max = 20) String password
) {
    // 실제 필드값 대신 고정 문자열을 반환해 이메일과 비밀번호의 노출을 방지한다.
    @Override
    public String toString() {
        return "OwnerLoginRequest[REDACTED]";
    }
}