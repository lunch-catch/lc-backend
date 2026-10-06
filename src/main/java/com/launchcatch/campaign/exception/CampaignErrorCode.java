package com.launchcatch.campaign.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 캠페인 애그리거트의 실패 코드. 명세는 docs/api-spec/campaign.md 의 "오류 코드" 절이다.
 * 코드는 기능 이슈에서 하나씩 더한다. 접두어마다 번호가 1부터 끊기지 않아야 한다.
 */
@Getter
@RequiredArgsConstructor
public enum CampaignErrorCode implements ErrorCode {
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}