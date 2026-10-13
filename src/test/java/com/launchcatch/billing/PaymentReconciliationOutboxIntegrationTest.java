package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.entity.PaymentReconciliationOutbox;
import com.launchcatch.billing.repository.PaymentReconciliationOutboxRepository;
import com.launchcatch.billing.service.PaymentOrderService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 재조회 대상 선점 쿼리를 실제 MySQL 8.4 로 검증한다.
 *
 * 목으로 확인할 수 없는 것을 본다. FOR UPDATE SKIP LOCKED 가 다른 트랜잭션이 잠근 행을 건너뛰는지,
 * 선점으로 미룬 다음 재조회 시각이 커밋되면 같은 행이 다시 잡히지 않는지다.
 * 커밋을 봐야 하므로 테스트 트랜잭션을 걸지 않고 매번 행을 지운다.
 */
@Testcontainers
@SpringBootTest
class PaymentReconciliationOutboxIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired PaymentReconciliationOutboxRepository repository;
    @Autowired PaymentOrderService orderService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void transactionTemplate() {
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from payment_reconciliation_outbox");
        jdbc.update("delete from payment");
    }

    // 외래키를 맞추려고 결제 주문을 만들고 그 결제의 아웃박스를 저장한다
    private Long outbox(long ownerId, LocalDateTime nextRetryAt) {
        Long paymentId = orderService.order(ownerId, 30000L).paymentId();
        repository.save(PaymentReconciliationOutbox.pending(paymentId, 5, nextRetryAt));
        return paymentId;
    }

    private List<Long> duePaymentIds(int limit) {
        return tx.execute(status -> repository.findDueForClaim(NOW, limit).stream()
                .map(PaymentReconciliationOutbox::getPaymentId).toList());
    }

    @Test
    void 시각이_된_대기_행만_오래_기다린_순서로_읽는다() {
        Long later = outbox(1L, NOW.minusMinutes(1));
        Long earlier = outbox(2L, NOW.minusMinutes(9));
        outbox(3L, NOW.plusMinutes(1));
        Long closed = outbox(4L, NOW.minusMinutes(5));
        jdbc.update("update payment_reconciliation_outbox set status = 'RESOLVED' where payment_id = ?", closed);

        assertThat(duePaymentIds(10)).containsExactly(earlier, later);
    }

    @Test
    void 한_번에_읽는_수는_상한을_넘지_않는다() {
        outbox(1L, NOW.minusMinutes(3));
        outbox(2L, NOW.minusMinutes(2));
        outbox(3L, NOW.minusMinutes(1));

        assertThat(duePaymentIds(2)).hasSize(2);
    }

    @Test
    void 선점으로_미룬_행은_커밋_뒤_다시_잡히지_않고_시각이_지나면_다시_잡힌다() {
        Long paymentId = outbox(1L, NOW.minusMinutes(1));

        tx.executeWithoutResult(status -> repository.findDueForClaim(NOW, 10)
                .forEach(row -> row.claim(NOW.plusMinutes(5))));

        assertThat(duePaymentIds(10)).isEmpty();
        List<Long> afterLease = tx.execute(status -> repository.findDueForClaim(NOW.plusMinutes(5), 10).stream()
                .map(PaymentReconciliationOutbox::getPaymentId).toList());
        assertThat(afterLease).containsExactly(paymentId);
    }

    @Test
    void 다른_트랜잭션이_잠근_행은_기다리지_않고_건너뛴다() throws Exception {
        Long first = outbox(1L, NOW.minusMinutes(3));
        Long second = outbox(2L, NOW.minusMinutes(2));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<Long>> holder = pool.submit(() -> tx.execute(status -> {
                List<Long> mine = repository.findDueForClaim(NOW, 1).stream()
                        .map(PaymentReconciliationOutbox::getPaymentId).toList();
                locked.countDown();
                await(release);
                return mine;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            List<Long> others = duePaymentIds(10);

            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).containsExactly(first);
            assertThat(others).containsExactly(second);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 여러_서버가_동시에_선점해도_같은_행을_두_번_집지_않는다() throws Exception {
        for (long owner = 1; owner <= 6; owner++) {
            outbox(owner, NOW.minusMinutes(owner));
        }
        int workers = 3;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<List<Long>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return tx.execute(status -> {
                        List<PaymentReconciliationOutbox> rows = repository.findDueForClaim(NOW, 2);
                        rows.forEach(row -> row.claim(NOW.plusMinutes(5)));
                        return rows.stream().map(PaymentReconciliationOutbox::getPaymentId).toList();
                    });
                }));
            }
            start.countDown();
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> result : results) {
                all.addAll(result.get(20, TimeUnit.SECONDS));
            }

            assertThat(all).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
