package com.launchcatch.adserving.engine.exception;

import com.launchcatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/*
 * 그날 후보 스냅샷으로 판정하는 실패다.
 * 스와이프와 가게 상세 찜이 함께 쓴다. 둘 다 engine 을 부르므로 한 방향을 지키려고 feed 가 아니라 여기에 둔다(#420).
 */
@Getter
@RequiredArgsConstructor
public enum CandidateErrorCode implements ErrorCode {

    CAMPAIGN_NOT_SERVING(HttpStatus.NOT_FOUND, "ADSERVING-004", "오늘 노출 중인 캠페인이 아닙니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
