package com.launchcatch.owner.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.OwnerStoreResponse;
import com.launchcatch.owner.service.OwnerStoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/store")
@RequiredArgsConstructor
public class OwnerStoreController {
    private final OwnerStoreService stores;

    @GetMapping
    @Operation(summary = "내 가게 조회", description = "미확정 초안을 포함한 본인 가게 정보를 조회합니다. 가게 생성 전에도 storeId가 null인 객체와 약관·진행 정보를 반환합니다.")
    public ResponseEnvelope<OwnerStoreResponse> getMyStore(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(stores.getMyStore(owner.getId()));
    }
}