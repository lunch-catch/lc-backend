package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.client.FakePaymentGateway;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult.Status;
import com.launchcatch.billing.client.PgUnknownException;
import com.launchcatch.billing.dto.AdminPaymentItem;
import com.launchcatch.billing.dto.PaymentWebhookRequest;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.service.AdminPaymentRecheckService;
import com.launchcatch.billing.service.PaymentConfirmationService;
import com.launchcatch.billing.service.PaymentOrderService;
import com.launchcatch.billing.service.PaymentRecheckService;
import com.launchcatch.billing.service.PaymentWebhookService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 웹훅, 재조회 워커, 관리자 재조회가 확인 중인 결제를 확정하는 경로를 실제 MySQL 8.4 로 검증한다.
 * 결제대행사만 가짜로 바꾼다.
 *
 * 목으로 확인할 수 없는 것을 본다. 세 경로가 동시에 같은 결제를 확정해도 충전이 한 번인지,
 * 관리자 재조회의 결과와 감사 로그가 함께 저장되는지다.
 * 커밋을 봐야 하므로 테스트 트랜잭션을 걸지 않고 매번 행을 지운다.
 */
@Testcontainers
@SpringBootTest
class PaymentRecheckIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    private static final Long OWNER = 7L;
    private static final Long ADMIN = 3L;
    private static final long AMOUNT = 30000L;
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 11, 10, 0, 3);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @TestConfiguration
    static class GatewayConfig {

        @Bean
        @Primary
        FakePaymentGateway fakePaymentGateway() {
            return new FakePaymentGateway();
        }
    }

    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmationService confirmationService;
    @Autowired PaymentRecheckService recheckService;
    @Autowired PaymentWebhookService webhookService;
    @Autowired AdminPaymentRecheckService adminRecheckService;
    @Autowired FakePaymentGateway gateway;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetGateway() {
        gateway.failConfirmWith(null).failInquiryWith(null);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from audit_log");
        jdbc.update("delete from point_ledger");
        jdbc.update("delete from payment_reconciliation_outbox");
        jdbc.update("delete from payment");
        jdbc.update("delete from point_account");
    }

    private record Order(Long paymentId, String orderId, String paymentKey) {
    }

    // 승인 응답을 못 받고 재조회도 확정하지 못해 확인 중이 된 결제를 만든다
    private Order unknownOrder(long ownerId, String paymentKey) {
        var response = orderService.order(ownerId, AMOUNT);
        gateway.failConfirmWith(new PgUnknownException("read timeout", new RuntimeException()));
        confirmationService.confirm(ownerId, response.paymentId(), response.orderId(), paymentKey);
        gateway.failConfirmWith(null);
        return new Order(response.paymentId(), response.orderId(), paymentKey);
    }

    private void pgSays(Order order, Status status) {
        gateway.givenPayment(new PaymentGatewayInquiryResult(
                status, order.paymentKey(), order.orderId(), AMOUNT, APPROVED_AT, null, null));
    }

    private void makeDue(Long paymentId) {
        jdbc.update("update payment_reconciliation_outbox set next_retry_at = '2000-01-01 00:00:00' "
                + "where payment_id = ? and status = 'PENDING'", paymentId);
    }

    private int charges(Long paymentId) {
        return jdbc.queryForObject("select count(*) from point_ledger where payment_id = ? and entry_type = 'CHARGE'",
                Integer.class, paymentId);
    }

    private String status(Long paymentId) {
        return jdbc.queryForObject("select status from payment where payment_id = ?", String.class, paymentId);
    }

    private String outboxStatus(Long paymentId) {
        return jdbc.queryForObject("select status from payment_reconciliation_outbox where payment_id = ?",
                String.class, paymentId);
    }

    @Test
    void 웹훅과_워커와_관리자_재조회가_동시에_같은_결제를_확정해도_충전은_한_번이다() throws Exception {
        Order order = unknownOrder(OWNER, "tgen_race_1");
        pgSays(order, Status.APPROVED);
        makeDue(order.paymentId());
        PaymentWebhookRequest webhook = new PaymentWebhookRequest("PAYMENT_STATUS_CHANGED",
                new PaymentWebhookRequest.Data(order.paymentKey(), order.orderId()));
        List<Callable<Object>> tasks = List.of(
                () -> { webhookService.handle(webhook); return null; },
                () -> { webhookService.handle(webhook); return null; },
                () -> { recheckService.recheckDue(); return null; },
                () -> { adminRecheckService.recheck(ADMIN, order.paymentId()); return null; });
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        List<Future<Object>> results = new ArrayList<>();
        try {
            for (Callable<Object> task : tasks) {
                results.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Object> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    // 먼저 확정된 뒤에 온 관리자 재조회는 확인 중이 아니라서 거절된다
                    assertThat(e.getCause()).isInstanceOfSatisfying(BillingException.class,
                            cause -> assertThat(cause.getErrorCode()).isEqualTo(BillingErrorCode.PAYMENT_NOT_RECHECKABLE));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(order.paymentId())).isEqualTo("APPROVED");
        assertThat(charges(order.paymentId())).isEqualTo(1);
        assertThat(outboxStatus(order.paymentId())).isEqualTo("RESOLVED");
    }

    @Test
    void 확인_중인_결제를_관리자가_재조회하면_승인되고_감사_로그가_함께_남는다() {
        Order order = unknownOrder(OWNER, "tgen_admin_1");
        pgSays(order, Status.APPROVED);

        AdminPaymentItem item = adminRecheckService.recheck(ADMIN, order.paymentId());

        assertThat(item.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(item.reconciliation().status().name()).isEqualTo("RESOLVED");
        assertThat(charges(order.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'PAYMENT_RECHECK' "
                + "and target = ?", Integer.class, String.valueOf(order.paymentId()))).isEqualTo(1);
    }
}
