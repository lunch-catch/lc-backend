package com.launchcatch.billing.client;

/*
 * 요청이 PG에 나갔을 수 있고 결과를 알 수 없을 때 던진다.
 * 읽기 타임아웃, 5xx, 해석할 수 없는 응답, 거절로 단정할 수 없는 오류 코드가 여기에 든다.
 *
 * 실제로는 승인됐을 수 있으므로 받은 쪽은 결제를 실패로 단정하지 말고 PG 조회로 확인해야 한다.
 */
public class PgUnknownException extends RuntimeException {

    public PgUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
