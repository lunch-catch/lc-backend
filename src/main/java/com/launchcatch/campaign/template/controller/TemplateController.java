package com.launchcatch.campaign.template.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.campaign.template.dto.TemplateCreateRequest;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.service.TemplateCreateService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    @PostMapping
    @Operation(
            summary = "템플릿 생성",
            description = "관리자의 요청 문장으로 LLM 이 템플릿 HTML 을 만들고, 정화와 구조 검증을 통과한 결과를 임시저장 버전으로 저장합니다. 전체 템플릿은 10개로 제한됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "템플릿 생성 성공. DRAFT 템플릿과 1번 버전이 저장됩니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 이름이 비었거나 100자를 넘었거나 요청 문장이 비어 있습니다."),
            @ApiResponse(responseCode = "422", description = "POSTER-001: 슬롯이 빠졌거나 중복됨. POSTER-002: 팔레트에 없는 색상. POSTER-004: 템플릿이 이미 10개입니다."),
            @ApiResponse(responseCode = "503", description = "POSTER-005: LLM 응답이 30초를 넘었습니다.")
    })
    public ResponseEntity<ResponseEnvelope<TemplateCreateResponse>> create(
            @Valid @RequestBody TemplateCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        TemplateCreateResponse response =
                templateCreateService.create(request.name(), request.requestPrompt(), admin.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }
}
