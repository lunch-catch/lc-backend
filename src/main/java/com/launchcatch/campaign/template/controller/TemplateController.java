package com.launchcatch.campaign.template.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.campaign.template.dto.TemplateCreateRequest;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.dto.TemplateReviseRequest;
import com.launchcatch.campaign.template.service.TemplateCreateService;
import com.launchcatch.campaign.template.service.TemplateReviseService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/templates")
@RequiredArgsConstructor
@Tag(name = "템플릿 관리", description = "관리자가 포스터 템플릿을 만들고 관리하는 API입니다.")
public class TemplateController {

    private final TemplateCreateService templateCreateService;
    private final TemplateReviseService templateReviseService;

    @PostMapping
    @Operation(
            summary = "템플릿 생성",
            description = "관리자의 요청 문장으로 LLM 이 템플릿 HTML 을 만들고, 정화와 구조 검증을 통과한 결과를 임시저장 버전으로 저장합니다. 전체 템플릿은 10개로 제한됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "템플릿 생성 성공. DRAFT 템플릿과 1번 버전이 저장됩니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 이름이 비었거나 100자를 넘었거나, 요청 문장이 비었거나 500자를 넘었거나, 요청 식별자가 비었거나 64자를 넘었습니다."),
            @ApiResponse(responseCode = "422", description = "POSTER-001: 슬롯이 빠졌거나 중복됨. POSTER-003: 템플릿이 이미 10개입니다."),
            @ApiResponse(responseCode = "503", description = "POSTER-004: LLM 응답이 30초를 넘었습니다.")
    })
    public ResponseEntity<ResponseEnvelope<TemplateCreateResponse>> create(
            @Valid @RequestBody TemplateCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        TemplateCreateResponse response = templateCreateService.create(
                request.name(), request.requestPrompt(), request.requestId(), admin.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }

    @PostMapping("/{templateId}/versions")
    @Operation(
            summary = "템플릿 수정",
            description = "관리자의 요청 문장으로 LLM 이 해당 템플릿의 최신 버전을 다시 고치고, 정화·슬롯·구조 검증을 통과한 결과를 새 버전으로 저장합니다. 기존 버전은 지우지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "템플릿 수정 성공. 새 버전이 저장됩니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 요청 문장이 비었거나 500자를 넘었거나, 요청 식별자가 비었거나 64자를 넘었습니다."),
            @ApiResponse(responseCode = "404", description = "POSTER-005: 템플릿을 찾을 수 없습니다."),
            @ApiResponse(responseCode = "409", description = "POSTER-006: 게시된 템플릿은 수정할 수 없습니다."),
            @ApiResponse(responseCode = "422", description = "POSTER-001: 슬롯이 빠졌거나 중복됨. POSTER-002: 슬롯 구조가 이전 버전과 다릅니다."),
            @ApiResponse(responseCode = "503", description = "POSTER-004: LLM 응답이 30초를 넘었습니다.")
    })
    public ResponseEntity<ResponseEnvelope<TemplateCreateResponse>> revise(
            @Parameter(description = "수정할 템플릿 ID입니다.") @PathVariable Long templateId,
            @Valid @RequestBody TemplateReviseRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        TemplateCreateResponse response = templateReviseService.revise(
                templateId, request.requestPrompt(), request.requestId(), admin.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }
}
