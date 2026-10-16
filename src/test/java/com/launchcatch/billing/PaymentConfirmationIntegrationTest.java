package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.billing.client.CommitCheckingGateway;
import com.launchcatch.billing.client.PgNotSentException;
import com.launchcatch.billing.client.PgUnknownException;
import com.launchcatch.billing.dto.PaymentConfirmResponse;
import com.launchcatch.billing.entity.PaymentStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.service.PaymentConfirmationService;
import com.launchcatch.billing.service.PaymentOrderService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 승인 확정을 실제 MySQL 8.4 와 실제 트랜잭션으로 검증한다. 결제대행사만 가짜로 바꾼다.
 *
 * 목으로 확인할 수 없는 것을 본다. 결제 식별자가 결제대행사 호출 전에 이미 커밋돼 있는지,
 * 결과 기록의 행 잠금이 동시 확정을 한 번의 충전으로 만드는지, UNKNOWN 과 아웃박스가 함께 저장되는지,
 * 결제 식별자 유일 제약과 점주 계정 행의 중복 흡수(ON DUPLICATE KEY UPDATE)가 실제로 동작하는지다.
 *
 * 커밋을 봐야 하므로 테스트 트랜잭션을 걸지 않고 매번 행을 지운다.
 */
class PaymentConfirmationIntegrationTest extends AbstractBillingIntegrationTest {

    private static final Long OWNER = 7L;
    private static final long AMOUNT = 30000L;



    @Autowired PaymentOrderService orderService;
    @Autowired PaymentConfirmationService confirmationService;
    @Autowired CommitCheckingGateway gateway;
    @Autowired JdbcTemplate jdbc;

    // 이 클래스만 승인 호출 시점의 커밋을 확인한다. 되돌리는 일은 기반 클래스가 한다.
    @BeforeEach
    void 커밋_확인을_켠다() {
        gateway.checkCommit();
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from point_ledger");
        jdbc.update("delete from payment_reconciliation_outbox");
        jdbc.update("delete from payment");
        jdbc.update("delete from point_account");
    }

    private record Order(Long paymentId, String orderId) {
    }

    private Order order(Long ownerId) {
        var response = orderService.order(ownerId, AMOUNT);
        return new Order(response.paymentId(), response.orderId());
    }

    private PaymentConfirmResponse confirm(Order order, String pgTid) {
        return confirmationService.confirm(OWNER, order.paymentId(), order.orderId(), pgTid);
    }

    private int charges(Long paymentId) {
        return jdbc.queryForObject("select count(*) from point_ledger where payment_id = ? and entry_type = 'CHARGE'",
                Integer.class, paymentId);
    }

    private String status(Long paymentId) {
        return jdbc.queryForObject("select status from payment where payment_id = ?", String.class, paymentId);
    }

    @Test
    void 승인되면_충전_한_줄과_점주_계정이_남고_결제_식별자는_호출_전에_이미_커밋돼_있다() {
        Order order = order(OWNER);

        PaymentConfirmResponse response = confirm(order, "tgen_approve_1");

        assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(gateway.pgTidsSeenAtCall()).containsExactly("tgen_approve_1");
        assertThat(status(order.paymentId())).isEqualTo("APPROVED");
        assertThat(charges(order.paymentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount from point_ledger where payment_id = ?",
                Long.class, order.paymentId())).isEqualTo(AMOUNT);
        assertThat(jdbc.queryForObject("select count(*) from point_account where owner_id = ?",
                Integer.class, OWNER)).isEqualTo(1);
    }

    @Test
    void 같은_주문을_다시_확정해도_결제대행사를_다시_부르지_않고_충전은_한_번이다() {
        Order order = order(OWNER);
        confirm(order, "tgen_twice_1");
        int callsAfterFirst = gateway.confirmCalls().size();

        PaymentConfirmResponse again = confirm(order, "tgen_twice_1");

        assertThat(again.status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(gateway.confirmCalls()).hasSize(callsAfterFirst);
        assertThat(charges(order.paymentId())).isEqualTo(1);
    }

    @Test
    void 같은_주문을_동시에_확정해도_충전은_한_번이고_결제는_승인으로_남는다() throws Exception {
        Order order = order(OWNER);
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<PaymentConfirmResponse>> results = new ArrayList<>();
        Callable<PaymentConfirmResponse> task = () -> {
            start.await();
            return confirm(order, "tgen_race_1");
        };
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(task));
        }

        start.countDown();
        for (Future<PaymentConfirmResponse> result : results) {
            assertThat(result.get().status()).isEqualTo(PaymentStatus.APPROVED);
        }
        pool.shutdown();

        assertThat(status(order.paymentId())).isEqualTo("APPROVED");
        assertThat(charges(order.paymentId())).isEqualTo(1);
    }

    @Test
    void 승인_응답을_기다리는_동안_다른_식별자로_확정하면_거절하고_최초_식별자로_한_번만_충전한다()
            throws Exception {
        Order order = order(OWNER);
        String firstPgTid = "tgen_first_attempt";
        CountDownLatch approvalStarted = new CountDownLatch(1);
        CountDownLatch finishApproval = new CountDownLatch(1);
        gateway.beforeApproval(paymentKey -> {
            if (!paymentKey.equals(firstPgTid)) {
                return;
            }
            approvalStarted.countDown();
            try {
                assertThat(finishApproval.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("승인 응답 대기가 중단됐습니다.", e);
            }
        });

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<PaymentConfirmResponse> first = pool.submit(() -> confirm(order, firstPgTid));
            try {
                assertThat(approvalStarted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(status(order.paymentId())).isEqualTo("REQUESTED");

                assertThatThrownBy(() -> confirm(order, "tgen_second_attempt"))
                        .isInstanceOfSatisfying(BillingException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.ORDER_ID_MISMATCH));
                assertThat(jdbc.queryForObject("select pg_tid from payment where payment_id = ?",
                        String.class, order.paymentId())).isEqualTo(firstPgTid);
                assertThat(gateway.pgTidsSeenAtCall()).containsExactly(firstPgTid);
            } finally {
                finishApproval.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(PaymentStatus.APPROVED);
        }

        assertThat(status(order.paymentId())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select pg_tid from payment where payment_id = ?",
                String.class, order.paymentId())).isEqualTo(firstPgTid);
        assertThat(charges(order.paymentId())).isEqualTo(1);
        assertThat(gateway.pgTidsSeenAtCall()).containsExactly(firstPgTid);
    }

    @Test
    void 응답이_불명이고_재조회로도_정하지_못하면_UNKNOWN과_재조회_아웃박스가_함께_저장된다() {
        Order order = order(OWNER);
        gateway.failConfirmWith(new PgUnknownException("read timeout", new RuntimeException()));

        PaymentConfirmResponse response = confirm(order, "tgen_unknown_1");

        assertThat(response.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(status(order.paymentId())).isEqualTo("UNKNOWN");
        assertThat(charges(order.paymentId())).isZero();
        Map<String, Object> outbox = jdbc.queryForMap(
                "select status, attempt_count, max_retry, next_retry_at from payment_reconciliation_outbox "
                        + "where payment_id = ?", order.paymentId());
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        assertThat(((Number) outbox.get("attempt_count")).intValue()).isZero();
        assertThat(((Number) outbox.get("max_retry")).intValue()).isEqualTo(5);
        assertThat(outbox.get("next_retry_at")).isNotNull();
    }

    @Test
    void 요청이_가지_않았으면_상태가_그대로라_같은_주문으로_다시_확정할_수_있다() {
        Order order = order(OWNER);
        gateway.failConfirmWith(new PgNotSentException("connection refused", new RuntimeException()));

        assertThatThrownBy(() -> confirm(order, "tgen_retry_1"))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.PG_REQUEST_NOT_SENT));
        assertThat(status(order.paymentId())).isEqualTo("REQUESTED");

        gateway.failConfirmWith(null);
        assertThat(confirm(order, "tgen_retry_1").status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(charges(order.paymentId())).isEqualTo(1);
    }

    @Test
    void 다른_결제가_이미_쓴_결제_식별자면_BILLING_017이고_그_주문은_승인_시도_전으로_남는다() {
        Order first = order(OWNER);
        confirm(first, "tgen_shared_1");
        Order second = order(OWNER);

        assertThatThrownBy(() -> confirm(second, "tgen_shared_1"))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.ORDER_ID_MISMATCH));
        assertThat(status(second.paymentId())).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("select pg_tid from payment where payment_id = ?",
                String.class, second.paymentId())).isNull();
    }

    @Test
    void 같은_점주가_두_번_충전해도_점주_계정_행은_하나다() {
        confirm(order(OWNER), "tgen_account_1");
        confirm(order(OWNER), "tgen_account_2");

        assertThat(jdbc.queryForObject("select count(*) from point_account where owner_id = ?",
                Integer.class, OWNER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from point_ledger where owner_id = ? and entry_type = 'CHARGE'",
                Integer.class, OWNER)).isEqualTo(2);
    }
}
