package com.lunchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lunchcatch.global.AbstractGlobalIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 앱의 쿼리 시간 제한이 실제 MySQL 에서 쿼리를 끊는지 본다(#1217).
 * 설정 키를 잘못 적으면 값은 들어가도 아무것도 끊지 않는다. 그래서 키가 아니라 끊기는 것을 본다.
 */
class DatabaseTimeoutIntegrationTest extends AbstractGlobalIntegrationTest {

    private static final String SLEEP_LONGER_THAN_QUERY_TIMEOUT = "SELECT SLEEP(7)";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("JdbcTemplate 의 쿼리가 5초를 넘으면 끊는다")
    void JdbcTemplate_쿼리는_5초에_끊긴다() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> jdbcTemplate.queryForObject(SLEEP_LONGER_THAN_QUERY_TIMEOUT, Integer.class))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("JPA 의 쿼리가 5초를 넘으면 끊는다")
    void JPA_쿼리는_5초에_끊긴다() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> entityManager.createNativeQuery(SLEEP_LONGER_THAN_QUERY_TIMEOUT).getSingleResult())
                .isInstanceOf(jakarta.persistence.QueryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(7));
    }

    // Spring 은 트랜잭션 안의 쿼리에 쿼리 시간 제한 대신 트랜잭션의 남은 시간을 건다. 트랜잭션 제한도 5초라 안에서도 5초에 끊긴다
    @Test
    @DisplayName("트랜잭션 안의 JdbcTemplate 쿼리도 5초를 넘으면 끊는다")
    void 트랜잭션_안의_JdbcTemplate_쿼리도_5초에_끊긴다() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                jdbcTemplate.queryForObject(SLEEP_LONGER_THAN_QUERY_TIMEOUT, Integer.class)))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("트랜잭션 안의 JPA 쿼리도 5초를 넘으면 끊는다")
    void 트랜잭션_안의_JPA_쿼리도_5초에_끊긴다() {
        long start = System.nanoTime();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery(SLEEP_LONGER_THAN_QUERY_TIMEOUT).getSingleResult()))
                .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(7));
    }

    // 따로 정한 @Transactional(timeout) 이 없는 트랜잭션이 이 값을 쓴다
    @Test
    @DisplayName("트랜잭션의 기본 시간 제한은 5초다")
    void 트랜잭션_기본_시간_제한은_5초다() {
        assertThat(((AbstractPlatformTransactionManager) transactionManager).getDefaultTimeout()).isEqualTo(5);
    }
}
