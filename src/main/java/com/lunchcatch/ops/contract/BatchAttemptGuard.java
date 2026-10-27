package com.lunchcatch.ops.contract;

import java.time.LocalDate;

/**
 * 실행 행의 소유자와 실행 세대가 지금도 맞는지 확인한다(#360).
 *
 * 호출자가 자기 결과를 공개하는 트랜잭션 안에서 부른다. succeed/fail의 소유자 조건만으로는
 * 임대를 잃은 서버를 막지 못한다. 그 조건은 결과가 이미 커밋된 뒤에야 걸린다. 맞지 않으면
 * 예외를 던져 호출자의 트랜잭션을 롤백시킨다.
 */
public interface BatchAttemptGuard {

    void assertCurrentAttempt(String jobName, LocalDate businessDate, int attemptNo);
}
