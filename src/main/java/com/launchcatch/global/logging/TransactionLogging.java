package com.launchcatch.global.logging;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 상태 변경 성공 로그를 커밋 이후에 남겨 롤백된 작업이 성공으로 기록되지 않게 한다.
public final class TransactionLogging {

    private TransactionLogging() {
    }

    public static void afterCommit(Runnable logAction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            logAction.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                logAction.run();
            }
        });
    }

    // 롤백되거나 커밋 결과가 확정되지 않은 트랜잭션의 실패를 기록한다.
    public static void afterFailure(Runnable logAction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    logAction.run();
                }
            }
        });
    }
}
