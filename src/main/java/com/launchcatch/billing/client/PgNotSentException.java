package com.launchcatch.billing.client;

/*
 * 요청이 PG에 나가지 않았음이 확실할 때 던진다.
 * 연결 실패, DNS 실패, 서킷 오픈이 여기에 든다. PG가 요청을 받지 못했으니 승인됐을 가능성이 없고,
 * 받은 쪽은 같은 요청을 다시 보내도 된다.
 *
 * 확실하지 않으면 이 예외를 쓰지 않는다. 그런 실패는 PgUnknownException이다.
 */
public class PgNotSentException extends RuntimeException {

    public PgNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
