package com.launchcatch.store.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.store.dto.StoreImageConfirmUploadRequest;
import com.launchcatch.store.dto.StoreImagePrepareUploadRequest;
import com.launchcatch.store.dto.StoreImagePrepareUploadResponse;
import com.launchcatch.store.dto.StoreImagesCompleteRequest;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.service.StoreImageUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner")
@RequiredArgsConstructor
@Tag(name = "가게 인테리어 이미지", description = "점주가 가게 인테리어 이미지를 등록하는 API입니다.")
public class StoreImageController {

    private final StoreImageUploadService storeImageUploadService;

    @PostMapping("/store-images:prepareUpload")
    @Operation(
            summary = "인테리어 이미지 업로드 URL 발급",
            description = "presigned PUT URL을 발급합니다. 이미지 한 장당 호출합니다."
    )
    public ResponseEnvelope<StoreImagePrepareUploadResponse> prepareUpload(
            @Valid @RequestBody StoreImagePrepareUploadRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var result = storeImageUploadService.prepareInteriorUpload(
                owner.getId(), request.originalFilename(), request.contentType(), request.fileSize());
        return ResponseEnvelope.success(StoreImagePrepareUploadResponse.from(result));
    }

    @PostMapping("/store-images:confirmUpload")
    @Operation(
            summary = "인테리어 이미지 업로드 확인",
            description = "실제로 업로드됐는지 S3Client.headObject로 확인합니다. 이미지 한 장당 호출합니다."
    )
    public ResponseEnvelope<Void> confirmUpload(
            @Valid @RequestBody StoreImageConfirmUploadRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        storeImageUploadService.confirmUpload(owner.getId(), request.uploadId());
        return ResponseEnvelope.success();
    }

    @PostMapping("/store-images:complete")
    @Operation(
            summary = "이미지 등록 단계 완료",
            description = "인테리어 이미지(0~3개) 등록을 마치고 IMAGES 단계를 완료합니다. MENU로 진행합니다."
    )
    public ResponseEnvelope<StoreRegistrationProgressResponse> complete(
            @Valid @RequestBody StoreImagesCompleteRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var response = storeImageUploadService.completeImagesStep(owner.getId(), request.version());
        return ResponseEnvelope.success(response);
    }
}
