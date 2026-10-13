package com.launchcatch.store.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StoreErrorCode implements ErrorCode {
    KAKAO_PLACE_SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STORE-001", "일시적으로 가게를 검색할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    KAKAO_PLACE_SEARCH_FAILED(HttpStatus.BAD_GATEWAY, "STORE-002", "가게 검색 결과를 가져올 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
