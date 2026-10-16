package com.launchcatch.store.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.service.StoreRegistrationProgressQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/store-registration-progress")
@RequiredArgsConstructor
@Tag(name = "가게 등록 진행 상태", description = "점주가 자신의 가게 등록 진행 상태를 조회하는 API입니다.")
public class StoreRegistrationProgressController {

    private final StoreRegistrationProgressQueryService progressQueryService;

    @GetMapping
    @Operation(
            summary = "가게 등록 진행 상태 조회",
            description = "이어서 진행할 단계와 다음 완료 요청에 실어 보낼 버전을 돌려줍니다."
    )
    public ResponseEnvelope<StoreRegistrationProgressResponse> get(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        return ResponseEnvelope.success(progressQueryService.getProgress(owner.getId()));
    }
}
