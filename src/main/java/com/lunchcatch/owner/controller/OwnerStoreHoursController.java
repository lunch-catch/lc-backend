package com.lunchcatch.owner.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.owner.dto.StoreHoursRequest;
import com.lunchcatch.owner.dto.StoreHoursResponse;
import com.lunchcatch.owner.service.OwnerStoreHoursService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerStoreHoursController {
    private final OwnerStoreHoursService hours;

    @PutMapping("/v1/owner/store/hours")
    @Operation(summary = "매장 영업시간 등록·수정", description = "영업일만 받아 누락한 요일은 휴무·시각 null로 채워 7일 전체를 덮어씁니다. 당일 영업만 지원하며 선택 시각은 생략할 수 있습니다. ONBOARDING은 version이 필수이고 ACTIVE는 생략합니다.")
    public ResponseEnvelope<StoreHoursResponse> save(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner,
            @Valid @RequestBody StoreHoursRequest request) {
        return ResponseEnvelope.success(hours.save(owner.getId(), request));
    }
}
