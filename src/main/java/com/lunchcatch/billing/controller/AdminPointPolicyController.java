package com.lunchcatch.billing.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.billing.dto.AdminPointPolicyItem;
import com.lunchcatch.billing.dto.AdminPointPolicyResponse;
import com.lunchcatch.billing.dto.PointPolicySaveRequest;
import com.lunchcatch.billing.service.AdminPointPolicyService;
import com.lunchcatch.global.response.PageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/point-policy")
@RequiredArgsConstructor
@Tag(name = "관리자 포인트 정책", description = "관리자가 최소 충전 금액과 충전 상품을 조회하고 내일부터 적용할 정책을 저장하는 API입니다.")
public class AdminPointPolicyController {

    private final AdminPointPolicyService policyService;

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    @Operation(summary = "현재 정책과 내일 적용분 조회",
            description = "오늘 적용 중인 정책과 이미 저장된 내일 적용분을 돌려줍니다. 내일 적용분이 없으면 next 는 null 입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    public ResponseEnvelope<AdminPointPolicyResponse> getPolicy() {
        return ResponseEnvelope.success(policyService.getPolicy());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/history")
    @Operation(summary = "정책 변경 이력 조회",
            description = "적용일 내림차순 페이지 목록입니다. 크기는 기본 20, 최대 100이며 범위를 벗어나면 보정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 페이지 번호가 음수입니다.")
    })
    public ResponseEnvelope<PageResponse<AdminPointPolicyItem>> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEnvelope.success(policyService.history(page, size));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/next")
    @Operation(summary = "내일 적용할 정책 저장",
            description = "적용일은 서버가 내일로 정합니다. 같은 날 다시 저장하면 덮어씁니다. 변경 전후 값은 감사 로그에 남습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 성공. 저장된 내일 적용분을 돌려줍니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 최소 충전 금액이 0 이하이거나, 상품이 없거나 최소 충전 금액보다 작거나, 사유가 255자를 넘습니다.")
    })
    public ResponseEnvelope<AdminPointPolicyItem> saveNext(
            @AuthenticationPrincipal CustomUserDetails admin,
            @Valid @RequestBody PointPolicySaveRequest request) {
        return ResponseEnvelope.success(policyService.save(admin.getId(), request));
    }
}
