package com.lunchcatch.auth;

import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/*
 * 토큰 저장소(DB 와 Redis)의 실패를 응답으로 가른다(#1320).
 * 기다리면 나아질 실패는 AUTH-002(503)로 바꾸고, 코드 버그는 원래 예외를 돌려 전역 처리기의 500 이 되게 한다.
 *
 * 기다리면 나아질 실패는 커넥션을 얻지 못함(CannotCreateTransactionException), DB 나 Redis 를 쓸 수 없음
 * (DataAccessResourceFailureException), 잠금 대기와 시간 초과(TransientDataAccessException)다.
 * 커넥션을 얻지 못한 실패는 DataAccessException 이 아니라 TransactionException 으로 와서 따로 둔다.
 * 토큰의 DB 쓰기는 같은 계정 행의 CAS 라 잠금 대기를 일시적인 것으로 본다.
 * SQL 문법 오류, 제약 위반, 트랜잭션 전파 위반은 다시 해도 같아 바꾸지 않는다. 바꾸면 버그가 "잠시 후 다시" 로 숨는다.
 */
public final class StoreFailures {

    private StoreFailures() {
    }

    // 던질 예외를 돌려준다. 기다리면 나아질 실패는 원래 예외를 원인으로 단 AUTH-002, 그 밖은 원래 예외 그대로다
    public static RuntimeException toResponse(RuntimeException failure) {
        if (failure instanceof CannotCreateTransactionException
                || failure instanceof DataAccessResourceFailureException
                || failure instanceof TransientDataAccessException) {
            return new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE, failure);
        }
        return failure;
    }
}
