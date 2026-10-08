package com.launchcatch.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "관리자 로그인 요청")
public record AdminLoginRequest(
        @Schema(description = "관리자 로그인 아이디", example = "admin01")
        @NotBlank(message = "아이디를 입력해 주세요.")
        @Size(max = 50, message = "아이디는 50자를 넘을 수 없습니다.")
        String loginId,
        @Schema(description = "비밀번호. UTF-8 72바이트 초과 시 AUTH-001로 로그인 실패", example = "Freshman!2026")
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        String password
) {
    @Override
    public String toString() {
        return "AdminLoginRequest[loginId=" + loginId + ", password=****]";
    }
}
