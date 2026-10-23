package com.launchcatch.campaign.poster.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.campaign.poster.dto.PosterCreateRequest;
import com.launchcatch.campaign.poster.dto.PosterCreateResponse;
import com.launchcatch.campaign.poster.dto.PosterImagePrepareUploadRequest;
import com.launchcatch.campaign.poster.dto.PosterImagePrepareUploadResponse;
import com.launchcatch.campaign.poster.dto.PosterUpdateRequest;
import com.launchcatch.campaign.poster.service.PosterEditService;
import com.launchcatch.campaign.poster.service.PosterQueryService;
import com.launchcatch.campaign.poster.service.PosterUploadService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/campaigns/{campaignId}")
@RequiredArgsConstructor
@Tag(name = "점주 포스터", description = "점주가 DRAFT 캠페인의 포스터를 생성하고 조회하는 API입니다.")
public class OwnerPosterController {

    private final PosterUploadService posterUploadService;
    private final PosterQueryService posterQueryService;
    private final PosterEditService posterEditService;

    @PostMapping("/poster:prepareUpload")
    @Operation(
            summary = "포스터 이미지 업로드 URL 발급",
            description = "presigned PUT URL을 발급합니다. 캠페인이 DRAFT일 때만 가능합니다."
    )
    public ResponseEnvelope<PosterImagePrepareUploadResponse> prepareUpload(
            @PathVariable Long campaignId,
            @Valid @RequestBody PosterImagePrepareUploadRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var response = posterUploadService.prepareImageUpload(
                owner.getId(), campaignId, request.contentType(), request.fileSize());
        return ResponseEnvelope.success(response);
    }

    @PostMapping("/poster")
    @Operation(
            summary = "포스터 생성",
            description = "templateId와 업로드한 이미지의 objectKey로 포스터를 생성합니다. "
                    + "캠페인에 이미 포스터가 있으면 덮어씁니다."
    )
    public ResponseEnvelope<PosterCreateResponse> create(
            @PathVariable Long campaignId,
            @Valid @RequestBody PosterCreateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var response = posterUploadService.createOrOverwritePoster(
                owner.getId(), campaignId, request.templateId(), request.objectKey());
        return ResponseEnvelope.success(response);
    }

    @PatchMapping("/poster")
    @Operation(
            summary = "포스터 텍스트와 글자 크기 수정",
            description = "가게명 텍스트와 글자 크기, 할인 문구 뒤에 붙일 텍스트와 글자 크기를 고칩니다. 부분 수정이라 보내지 "
                    + "않은 필드는 바꾸지 않습니다. 시간과 이미지는 고칠 수 없습니다. 캠페인이 DRAFT일 때만 가능합니다."
    )
    public ResponseEnvelope<PosterCreateResponse> update(
            @PathVariable Long campaignId,
            @RequestBody PosterUpdateRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var response = posterEditService.update(owner.getId(), campaignId, request);
        return ResponseEnvelope.success(response);
    }

    @GetMapping("/poster")
    @Operation(
            summary = "점주용 포스터 조회",
            description = "본인 소유 캠페인에 저장된 포스터를 조회합니다. 캠페인 상태와 무관합니다."
    )
    public ResponseEnvelope<PosterCreateResponse> get(
            @PathVariable Long campaignId,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var response = posterQueryService.findForOwner(owner.getId(), campaignId);
        return ResponseEnvelope.success(response);
    }
}
