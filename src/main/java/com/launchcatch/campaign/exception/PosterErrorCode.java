package com.launchcatch.campaign.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 포스터와 템플릿 애그리거트의 실패 코드.
 * 명세는 docs/api-spec/poster.md 의 "오류 코드" 절이다.
 */
@Getter
@RequiredArgsConstructor
public enum PosterErrorCode implements ErrorCode {
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
