package com.lunchcatch.owner.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.owner.dto.StoreCompleteRequest;
import com.lunchcatch.owner.dto.StoreCompleteResponse;
import com.lunchcatch.owner.service.OwnerStoreCompleteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerStoreCompleteController {
    private final OwnerStoreCompleteService completion;

    @PostMapping("/v1/owner/store:complete")
    @Operation(summary = "가게 최종 등록", description = "필수 저장 정보를 재검증하고 가게 확정과 점주 ACTIVE 전환을 함께 처리합니다. 메뉴는 없어도 되며 최신 version이 필요합니다. 이미 완료한 점주의 재요청은 거절합니다.")
    public ResponseEnvelope<StoreCompleteResponse> complete(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner,
            @Valid @RequestBody StoreCompleteRequest request) {
        return ResponseEnvelope.success(completion.complete(owner.getId(), request));
    }
}
