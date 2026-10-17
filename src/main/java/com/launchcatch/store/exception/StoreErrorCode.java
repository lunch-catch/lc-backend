package com.launchcatch.store.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoreErrorCode implements ErrorCode {
    REGISTRATION_VERSION_CONFLICT(HttpStatus.CONFLICT, "STORE-001", "다른 화면에서 정보가 변경되었습니다. 최신 정보를 확인해 주세요."),
    REGISTRATION_STEP_CONFLICT(HttpStatus.CONFLICT, "STORE-002", "현재 진행 단계와 요청한 단계가 일치하지 않습니다."),
    REGISTRATION_PROGRESS_NOT_FOUND(HttpStatus.CONFLICT, "STORE-003", "가게 등록 진행 상태를 먼저 초기화해야 합니다."),
    REGISTRATION_STORE_NOT_ALLOWED(HttpStatus.FORBIDDEN, "STORE-004", "해당 가게를 등록 진행 상태에 연결할 수 없습니다."),
    TERMS_ALREADY_RECORDED(HttpStatus.CONFLICT, "TERMS-001", "이미 저장된 약관 동의 내역입니다."),
    IMAGE_CONTENT_TYPE_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY, "STORE-005", "jpg, png 파일만 업로드할 수 있습니다."),
    IMAGE_FILE_TOO_LARGE(HttpStatus.UNPROCESSABLE_ENTITY, "STORE-006", "파일 크기는 10MB를 넘을 수 없습니다."),
    INTERIOR_IMAGE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "STORE-007", "인테리어 이미지는 최대 3개까지 등록할 수 있습니다."),
    IMAGE_UPLOAD_NOT_FOUND(HttpStatus.FORBIDDEN, "STORE-008", "그 업로드 요청에 접근할 권한이 없습니다."),
    IMAGE_NOT_UPLOADED(HttpStatus.UNPROCESSABLE_ENTITY, "STORE-009", "실제로 업로드되지 않았습니다. 다시 업로드해 주세요."),
    LOGO_IMAGE_REQUIRED(HttpStatus.CONFLICT, "STORE-010", "로고 이미지가 먼저 등록되어 있어야 합니다."),
    MENU_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "STORE-011", "메뉴는 최대 3개까지 등록할 수 있습니다."),
    MENU_NOT_FOUND(HttpStatus.FORBIDDEN, "STORE-012", "그 메뉴에 접근할 권한이 없습니다."),
    MENU_IMAGE_REQUIRED(HttpStatus.CONFLICT, "STORE-013", "등록한 메뉴에는 이미지가 모두 있어야 합니다."),
    MENU_IMAGE_ALREADY_EXISTS(HttpStatus.CONFLICT, "STORE-014", "이미 이미지가 등록된 메뉴입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}