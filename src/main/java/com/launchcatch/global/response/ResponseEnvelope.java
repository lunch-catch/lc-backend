package com.launchcatch.global.response;

import com.launchcatch.global.exception.ErrorCode;

/*
 * 모든 응답이 통과하는 공통 봉투.
 * 성공과 실패가 같은 모양이라 클라이언트가 분기 전에 파싱을 끝낼 수 있다.
 * code 와 message 는 실패일 때 ErrorCode 에서만 나온다. 던지는 자리에서 문장을 지어내지 않는다.
 * 성공 여부는 code 가 SUCCESS 인지로 판별한다. boolean 을 따로 두면 code 와 어긋난 응답이 나올 수 있다.
 */
public record ResponseEnvelope<T>(
        String code,
        String message,
        T data
) {

    private static final String SUCCESS_CODE = "SUCCESS";
    private static final String SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다.";

    // 반환할 값이 있는 성공 응답
    public static <T> ResponseEnvelope<T> success(T data) {
        return new ResponseEnvelope<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    // 반환할 값이 없는 성공 응답
    public static ResponseEnvelope<Void> success() {
        return new ResponseEnvelope<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    // 실패 응답
    public static ResponseEnvelope<Void> fail(ErrorCode errorCode) {
        return new ResponseEnvelope<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /*
     * 클라이언트가 code 만으로 화면을 못 그릴 때만 쓴다. 아니면 fail(ErrorCode) 가 기본이다.
     * data 에는 필드가 고정된 record 만 넣는다. 키가 변하는 Map 은 계약이 되지 않는다.
     */
    public static <T> ResponseEnvelope<T> fail(ErrorCode errorCode, T data) {
        return new ResponseEnvelope<>(errorCode.getCode(), errorCode.getMessage(), data);
    }
}
