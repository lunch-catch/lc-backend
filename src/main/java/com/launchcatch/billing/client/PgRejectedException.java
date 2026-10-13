package com.launchcatch.billing.client;

/*
 * PG가 승인을 확실히 거절했을 때 던진다.
 * 재시도해도 같은 결과가 나오므로 받은 쪽은 결제를 실패로 확정해도 된다.
 *
 * 에러 코드로 바꾸는 일은 결제 프로세스 작업이 한다. 여기서는 PG가 준 코드와 사유만 담는다.
 */
public class PgRejectedException extends RuntimeException {

    private final String pgCode;

    public PgRejectedException(String pgCode, String message) {
        super(message);
        this.pgCode = pgCode;
    }

    public String getPgCode() {
        return pgCode;
    }
}
