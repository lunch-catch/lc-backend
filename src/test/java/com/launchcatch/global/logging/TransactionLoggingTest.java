package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class TransactionLoggingTest {

    @Test
    void 트랜잭션이_없으면_즉시_기록한다() {
        List<String> events = new ArrayList<>();

        TransactionLogging.afterCommit(() -> events.add("성공"));
        TransactionLogging.afterFailure(() -> events.add("실패"));

        assertThat(events).containsExactly("성공");
    }

    @Test
    void 성공_기록은_커밋까지_대기한다() {
        List<String> events = new ArrayList<>();
        TransactionTemplate transaction = new TransactionTemplate(new TestTransactionManager());

        transaction.executeWithoutResult(status -> {
            TransactionLogging.afterCommit(() -> events.add("성공"));
            TransactionLogging.afterFailure(() -> events.add("실패"));
            assertThat(events).isEmpty();
        });

        assertThat(events).containsExactly("성공");
    }

    @Test
    void 롤백된_작업은_성공으로_기록하지_않는다() {
        List<String> events = new ArrayList<>();
        TransactionTemplate transaction = new TransactionTemplate(new TestTransactionManager());

        transaction.executeWithoutResult(status -> {
            TransactionLogging.afterCommit(() -> events.add("성공"));
            TransactionLogging.afterFailure(() -> events.add("실패"));
            status.setRollbackOnly();
        });

        assertThat(events).containsExactly("실패");
    }

    @Test
    void 커밋에_실패한_작업은_성공으로_기록하지_않는다() {
        List<String> events = new ArrayList<>();
        TestTransactionManager manager = new TestTransactionManager();
        manager.failCommit = true;
        TransactionTemplate transaction = new TransactionTemplate(manager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            TransactionLogging.afterCommit(() -> events.add("성공"));
            TransactionLogging.afterFailure(() -> events.add("실패"));
        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(events).containsExactly("실패");
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        private boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new IllegalStateException("커밋 실패");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
