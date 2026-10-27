package com.lunchcatch.billing.client;

/*
 * PG가 요청을 처리하지 않았음이 확실할 때 던진다.
 * 요청이 나가지 않은 경우(연결 실패, DNS 실패, 커넥션 풀 대기 초과, 서킷 오픈)와
 * PG가 인증 단계에서 거부해 처리하지 않은 경우(401, 403)가 여기에 든다.
 * 어느 쪽이든 승인됐을 가능성이 없으므로 받은 쪽은 같은 요청을 다시 보내도 된다.
 *
 * 확실하지 않으면 이 예외를 쓰지 않는다. 그런 실패는 PgUnknownException이다.
 */
public class PgNotSentException extends RuntimeException {

    public PgNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
