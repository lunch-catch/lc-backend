package com.launchcatch.owner.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.StoreLocationRequest;
import com.launchcatch.owner.dto.StoreLocationResponse;
import com.launchcatch.owner.service.OwnerStoreLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerStoreLocationController {
    private final OwnerStoreLocationService locations;

    @Operation(summary = "온보딩 매장 위치 저장", description = "ONBOARDING 점주의 확정 위치를 저장합니다. 최초 저장은 HOURS로 진행하며 재저장은 기존 재개 단계를 유지합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 완료: storeId, resumeStep, 증가한 version"),
            @ApiResponse(responseCode = "400", description = "필수값, 문자열 길이, 좌표 범위 또는 버전 오류"),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 미인증 또는 점주 계정 부재"),
            @ApiResponse(responseCode = "403", description = "AUTH-006: OWNER 외 역할, OWNER-002: 상태 오류, STORE-004: 본인 초안 연결 오류"),
            @ApiResponse(responseCode = "409", description = "STORE-001: 버전 충돌, STORE-002: 선행 단계 미완료, STORE-003: 진행 상태 없음")
    })
    @PutMapping("/v1/owner/store/location")
    public ResponseEnvelope<StoreLocationResponse> save(@AuthenticationPrincipal CustomUserDetails user,
                                                       @Valid @RequestBody StoreLocationRequest request) {
        return ResponseEnvelope.success(locations.save(user.getId(), request));
    }
}
