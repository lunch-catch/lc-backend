package com.launchcatch.store.controller;

import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.store.dto.StoreDetailResponse;
import com.launchcatch.store.service.StoreDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class StoreDetailController {
    private final StoreDetailService stores;

    @Operation(summary = "사용자 가게 상세 조회", description = "MEMBER가 최종 등록된 가게와 당일 활성 캠페인을 조회한다.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 가게 ID"),
            @ApiResponse(responseCode = "401", description = "인증 필요"),
            @ApiResponse(responseCode = "403", description = "MEMBER 외 역할"),
            @ApiResponse(responseCode = "404", description = "미존재 또는 미등록 가게")})
    @GetMapping("/v1/stores/{storeId}")
    ResponseEnvelope<StoreDetailResponse> find(@PathVariable @Positive long storeId) {
        return ResponseEnvelope.success(stores.find(storeId));
    }
}
