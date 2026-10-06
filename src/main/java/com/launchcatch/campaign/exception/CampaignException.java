package com.launchcatch.campaign.exception;

import com.launchcatch.global.exception.BusinessException;
import com.launchcatch.global.exception.ErrorCode;

/*
 * 캠페인 도메인의 실패를 던질 때 쓴다. 도메인마다 예외 클래스는 하나이고, 오류 코드 enum 이 둘이라
 * 두 enum 을 모두 받도록 ErrorCode 로 받는다. BusinessException 이 추상이라 구체 타입이 필요하다.
 */
public class CampaignException extends BusinessException {

    public CampaignException(ErrorCode errorCode) {
        super(errorCode);
    }

    public CampaignException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}