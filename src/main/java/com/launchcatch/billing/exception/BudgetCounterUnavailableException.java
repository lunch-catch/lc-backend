package com.launchcatch.billing.exception;

/*
 * 예산 진행률 조회 중 당일 소진 카운터(Valkey)를 읽거나 재구성하지 못했을 때 던진다.
 * 연결 실패, 시간 초과처럼 다시 불러 보면 될 장애만 여기 담는다. 재구성한 뒤에도 해시가
 * 없는 데이터 불일치는 이 예외가 아니라 IllegalStateException 으로 그대로 올라간다.
 *
 * 대체값 없이 전파하는 결정은 billing_장애_처리_설계.md 1장을 따른다. 이 타입은 그
 * 결정을 바꾸지 않고, 호출하는 도메인이 다시 해 보면 될 장애와 프로그래밍 오류를
 * 가려 잡을 수 있게 이름만 붙인다.
 */
public class BudgetCounterUnavailableException extends RuntimeException {

    public BudgetCounterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
