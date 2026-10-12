package com.launchcatch.campaign.template.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.campaign.template.dto.TemplateActiveUpdateRequest;
import com.launchcatch.campaign.template.dto.TemplateActiveUpdateResponse;
import com.launchcatch.campaign.template.dto.TemplateCreateRequest;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.dto.TemplateDetailResponse;
import com.launchcatch.campaign.template.dto.TemplateListItemResponse;
import com.launchcatch.campaign.template.dto.TemplatePublishRequest;
import com.launchcatch.campaign.template.dto.TemplatePublishResponse;
import com.launchcatch.campaign.template.dto.TemplateReviseRequest;
import com.launchcatch.campaign.template.dto.TemplateVersionItemResponse;
import com.launchcatch.campaign.template.service.TemplateActivationService;
import com.launchcatch.campaign.template.service.TemplateCreateService;
import com.launchcatch.campaign.template.service.TemplatePublishService;
import com.launchcatch.campaign.template.service.TemplateQueryService;
import com.launchcatch.campaign.template.service.TemplateReviseService;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/templates")
@RequiredArgsConstructor
@Tag(name = "템플릿 관리", description = "관리자가 포스터 템플릿을 만들고 관리하는 API입니다.")
public class TemplateController {

    private final TemplateCreateService templateCreateService;
    private final TemplateReviseService templateReviseService;
    private final TemplatePublishService templatePublishService;
    private final TemplateActivationService templateActivationService;
    private final TemplateQueryService templateQueryService;

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

    @PostMapping("/{templateId}/publish")
    @Operation(
            summary = "템플릿 게시",
            description = "DRAFT 템플릿의 임시저장 버전 중 하나를 선택해 최종 콘텐츠로 확정합니다. 게시하면 이 템플릿의 임시저장 버전은 모두 삭제됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "템플릿 게시 성공. 선택한 버전의 HTML이 확정되고 모든 버전이 삭제됩니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: versionNumber가 비었거나 양수가 아닙니다."),
            @ApiResponse(responseCode = "404", description = "POSTER-005: 템플릿을 찾을 수 없습니다. POSTER-008: 존재하지 않는 버전 번호입니다."),
            @ApiResponse(responseCode = "409", description = "POSTER-007: 이미 게시된 템플릿입니다.")
    })
    public ResponseEntity<ResponseEnvelope<TemplatePublishResponse>> publish(
            @Parameter(description = "게시할 템플릿 ID입니다.") @PathVariable Long templateId,
            @Valid @RequestBody TemplatePublishRequest request) {
        TemplatePublishResponse response = templatePublishService.publish(templateId, request.versionNumber());
        return ResponseEntity.ok(ResponseEnvelope.success(response));
    }

    @PatchMapping("/{templateId}/active")
    @Operation(
            summary = "템플릿 활성화 관리",
            description = "게시된 템플릿의 점주 노출 여부(활성 상태)를 변경합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "활성화 상태 변경 성공."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: active가 비었습니다."),
            @ApiResponse(responseCode = "404", description = "POSTER-005: 템플릿을 찾을 수 없습니다."),
            @ApiResponse(responseCode = "409", description = "POSTER-009: 게시되지 않은 템플릿입니다.")
    })
    public ResponseEntity<ResponseEnvelope<TemplateActiveUpdateResponse>> changeActive(
            @Parameter(description = "활성화 상태를 바꿀 템플릿 ID입니다.") @PathVariable Long templateId,
            @Valid @RequestBody TemplateActiveUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails admin) {
        TemplateActiveUpdateResponse response =
                templateActivationService.changeActive(templateId, request.active(), admin.getId());
        return ResponseEntity.ok(ResponseEnvelope.success(response));
    }

    @GetMapping
    @Operation(
            summary = "템플릿 목록 조회",
            description = "전체 템플릿을 생성일 최신순으로 조회합니다. html은 게시된 템플릿이면 게시된 HTML, DRAFT 템플릿이면 최신 버전의 HTML로, 미리보기에 씁니다. 템플릿은 최대 10개까지만 만들 수 있어 페이지네이션은 제공하지 않습니다."
    )
    @ApiResponse(responseCode = "200", description = "템플릿 목록 조회 성공.")
    public ResponseEntity<ResponseEnvelope<List<TemplateListItemResponse>>> list() {
        return ResponseEntity.ok(ResponseEnvelope.success(templateQueryService.list()));
    }

    @GetMapping("/{templateId}")
    @Operation(
            summary = "템플릿 상세 조회",
            description = "템플릿 상세를 조회합니다. html은 게시된 템플릿이면 게시된 HTML, DRAFT 템플릿이면 최신 버전의 HTML입니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "템플릿 상세 조회 성공."),
            @ApiResponse(responseCode = "404", description = "POSTER-005: 템플릿을 찾을 수 없습니다.")
    })
    public ResponseEntity<ResponseEnvelope<TemplateDetailResponse>> detail(
            @Parameter(description = "조회할 템플릿 ID입니다.") @PathVariable Long templateId) {
        return ResponseEntity.ok(ResponseEnvelope.success(templateQueryService.detail(templateId)));
    }

    @GetMapping("/{templateId}/versions")
    @Operation(
            summary = "템플릿 버전 목록 조회",
            description = "템플릿의 임시저장 버전을 최신 번호 순으로 커서 페이지네이션해 조회합니다. 게시한 템플릿은 임시저장 버전이 모두 삭제되어 빈 배열을 돌려줍니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "템플릿 버전 목록 조회 성공."),
            @ApiResponse(responseCode = "404", description = "POSTER-005: 템플릿을 찾을 수 없습니다.")
    })
    public ResponseEntity<ResponseEnvelope<CursorPageResponse<TemplateVersionItemResponse>>> versions(
            @Parameter(description = "조회할 템플릿 ID입니다.") @PathVariable Long templateId,
            @Parameter(description = "이전 응답의 nextPageToken입니다. 없으면 첫 페이지입니다.")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "한 번에 가져올 개수입니다. 기본 20, 최대 100입니다.")
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(
                ResponseEnvelope.success(templateQueryService.versions(templateId, pageToken, pageSize)));
    }
}
