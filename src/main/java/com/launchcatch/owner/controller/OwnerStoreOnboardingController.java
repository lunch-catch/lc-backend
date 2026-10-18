package com.launchcatch.owner.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.OwnerStoreOnboardingResponse;
import com.launchcatch.owner.service.OwnerStoreOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/onboarding")
@RequiredArgsConstructor
@Tag(name = "가게 등록 진행 상태", description = "점주가 자신의 가게 등록 진행 상태를 조회하는 API입니다.")
public class OwnerStoreOnboardingController {

    private final OwnerStoreOnboardingService progressQueryService;

    @GetMapping
    @Operation(
            summary = "가게 등록 진행 상태 조회",
            description = "약관을 포함한 단계별 완료 여부, 지금 진행할 단계와 최종 등록 가능 여부를 반환합니다."
    )
    public ResponseEnvelope<OwnerStoreOnboardingResponse> get(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(progressQueryService.getProgress(owner.getId()));
    }
}