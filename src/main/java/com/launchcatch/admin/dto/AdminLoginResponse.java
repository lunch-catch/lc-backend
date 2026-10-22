package com.launchcatch.admin.dto;

import com.launchcatch.auth.Role;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "관리자 로그인 응답")
public record AdminLoginResponse(
        @Schema(description = "관리자 식별자", example = "1") Long adminId,
        @Schema(description = "관리자 이름", example = "정규동") String name,
        @Schema(description = "관리자 권한", example = "SUPER_ADMIN") Role role
) {
    @Override
    public String toString() {
        return "AdminLoginResponse[adminId=" + adminId + ", name=****, role=" + role + "]";
    }
}
