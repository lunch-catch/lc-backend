package com.launchcatch.store.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.store.dto.StoreMenuImageConfirmUploadRequest;
import com.launchcatch.store.dto.StoreMenuImagePrepareUploadRequest;
import com.launchcatch.store.dto.StoreMenuImagePrepareUploadResponse;
import com.launchcatch.store.dto.StoreMenuRegisterRequest;
import com.launchcatch.store.dto.StoreMenuRegisterResponse;
import com.launchcatch.store.dto.StoreMenusCompleteRequest;
import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.service.StoreMenuUploadService;
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
@Tag(name = "가게 대표 메뉴", description = "점주가 가게 대표 메뉴와 메뉴 이미지를 등록하는 API입니다.")
public class StoreMenuController {

    private final StoreMenuUploadService storeMenuUploadService;

    @PostMapping("/store-menus")
    @Operation(
            summary = "대표 메뉴 등록",
            description = "메뉴명·가격·상세설명(선택)을 저장합니다. 메뉴 한 건당 호출합니다."
    )
    public ResponseEnvelope<StoreMenuRegisterResponse> register(
            @Valid @RequestBody StoreMenuRegisterRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var response = storeMenuUploadService.registerMenu(
                owner.getId(), request.name(), request.price(), request.description());
        return ResponseEnvelope.success(response);
    }

    @PostMapping("/store-menu-images:prepareUpload")
    @Operation(
            summary = "메뉴 이미지 업로드 URL 발급",
            description = "presigned PUT URL을 발급합니다. 메뉴 이미지 한 장당 호출합니다."
    )
    public ResponseEnvelope<StoreMenuImagePrepareUploadResponse> prepareUpload(
            @Valid @RequestBody StoreMenuImagePrepareUploadRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var result = storeMenuUploadService.prepareMenuImageUpload(
                owner.getId(), request.menuId(), request.originalFilename(), request.contentType(),
                request.fileSize());
        return ResponseEnvelope.success(StoreMenuImagePrepareUploadResponse.from(result));
    }

    @PostMapping("/store-menu-images:confirmUpload")
    @Operation(
            summary = "메뉴 이미지 업로드 확인",
            description = "실제로 업로드됐는지 S3Client.headObject로 확인합니다. 메뉴 이미지 한 장당 호출합니다."
    )
    public ResponseEnvelope<Void> confirmUpload(
            @Valid @RequestBody StoreMenuImageConfirmUploadRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        storeMenuUploadService.confirmMenuImageUpload(owner.getId(), request.uploadId());
        return ResponseEnvelope.success();
    }

    @PostMapping("/store-menus:complete")
    @Operation(
            summary = "메뉴 등록 단계 완료",
            description = "대표 메뉴(0~3개) 등록을 마치고 MENU 단계를 완료합니다."
    )
    public ResponseEnvelope<StoreRegistrationProgressResponse> complete(
            @Valid @RequestBody StoreMenusCompleteRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails owner) {
        var response = storeMenuUploadService.completeMenuStep(owner.getId(), request.version());
        return ResponseEnvelope.success(response);
    }
}
