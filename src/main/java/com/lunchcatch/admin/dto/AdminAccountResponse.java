package com.lunchcatch.admin.dto;

import com.lunchcatch.auth.Role;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "조회한 관리자 계정")
public record AdminAccountResponse(
        @Schema(description = "관리자 로그인 ID", example = "admin01") String loginId,
        @Schema(description = "관리자 이름", example = "홍길동") String name,
        @Schema(description = "관리자 권한", allowableValues = {"ADMIN", "SUPER_ADMIN"}) Role role
) { }
