package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.client.FakePaymentGateway;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult;
import com.launchcatch.billing.client.PaymentGatewayInquiryResult.Status;
import com.launchcatch.billing.repository.PaymentRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.PgChargeReconciliationPendingRepository;
import com.launchcatch.billing.service.PgChargeMismatchRecorder;
import com.launchcatch.billing.service.PgChargeReconciliationService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * PG_VS_CHARGE 대사(13번)의 입구를 실제 MySQL 8.4 로 검증한다. 입구는
 * PgChargeReconciliationService.reconcilePreviousDay 다. 작업 점유와 claim/succeed/fail 은
 * PgChargeReconciliationScheduler 의 단위 테스트(목)가 이미 본다.
 *
 * 목으로 확인할 수 없는 것을 본다. CHARGE 보정과 대사 불일치가 한 트랜잭션으로 함께 커밋되는지,
 * 같은 영업일로 다시 돌려도 네이티브 upsert 가 중복을 흡수하는지다.
 *
 * 영업일과 실행 시각을 실제 시계와 묶으면 이 테스트가 도는 날짜에 따라 워터마크 계산이 흔들린다.
 * 그래서 서비스를 고정 시계로 직접 만든다(DailyReleaseIntegrationTest 와 같은 이유). 결제대행사만
 * 가짜로 바꾼다.
 */
class PgChargeReconciliationIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 3, 10);
    private static final LocalDate RUN_DATE = DAY.plusDays(1);
    private static final String JOB = "TEST_BILLING_PG_VS_CHARGE_RECON";
    private static final long OWNER = 950_900L;

    @Autowired PaymentRepository paymentRepository;
    @Autowired PointLedgerRepository ledgerRepository;
    @Autowired PgChargeReconciliationPendingRepository pendingRepository;
    @Autowired PgChargeMismatchRecorder mismatchRecorder;
    @Autowired BatchExecutionQueryService batchExecutions;
    @Autowired MeterRegistry meterRegistry;
    @Autowired FakePaymentGateway gateway;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("delete from settlement_mismatch where check_type = 'PG_VS_CHARGE'"
                + " and target_id in (select payment_id from payment where owner_id = ?)", OWNER);
        jdbc.update("delete from point_ledger where owner_id = ?", OWNER);
        jdbc.update("delete from payment where owner_id = ?", OWNER);
    }

    // 전영업일(DAY) 안에 생성된 승인 결제다. pg_tid, 금액, 상태를 명시해 넣는다
    private long approvedPayment(String orderId, String pgTid, long amount) {
        jdbc.update("""
                insert into payment
                    (owner_id, order_id, pg_tid, amount, status, approved_at, created_at, updated_at)
                values (?, ?, ?, ?, 'APPROVED', ?, ?, ?)
                """, OWNER, orderId, pgTid, amount,
                DAY.atTime(10, 0), DAY.atTime(10, 0), DAY.atTime(10, 0));
        return jdbc.queryForObject("select payment_id from payment where order_id = ?", Long.class, orderId);
    }

    // DAY 00:00 에 이 작업이 도는 것으로 고정한다. 워터마크는 DAY 23:55 다
    private PgChargeReconciliationService serviceAtMidnightOf(LocalDate runDate) {
        Clock fixed = Clock.fixed(runDate.atStartOfDay().atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        return new PgChargeReconciliationService(paymentRepository, ledgerRepository, pendingRepository, gateway, mismatchRecorder,
                batchExecutions, meterRegistry, fixed);
    }

    private Long chargeAmount(long paymentId) {
        return jdbc.queryForObject(
                "select amount from point_ledger where payment_id = ? and entry_type = 'CHARGE'",
                Long.class, paymentId);
    }

    private int chargeCount(long paymentId) {
        return jdbc.queryForObject(
                "select count(*) from point_ledger where payment_id = ? and entry_type = 'CHARGE'",
                Integer.class, paymentId);
    }

    private record MismatchRow(String resolutionType, long expected, long actual) {
    }

    private MismatchRow mismatchRow(long paymentId) {
        return jdbc.queryForObject("""
                select resolution_type, expected_value, actual_value
                  from settlement_mismatch
                 where check_type = 'PG_VS_CHARGE' and target_id = ?
                """, (rs, rowNum) -> new MismatchRow(rs.getString("resolution_type"),
                rs.getLong("expected_value"), rs.getLong("actual_value")), paymentId);
    }

    private int mismatchCount(long paymentId) {
        return jdbc.queryForObject(
                "select count(*) from settlement_mismatch where check_type = 'PG_VS_CHARGE' and target_id = ?",
                Integer.class, paymentId);
    }

    @Test
    void PG_승인인데_CHARGE가_없으면_보정하고_재실행해도_중복되지_않는다() {
        long paymentId = approvedPayment("ord_pg_recon_1", "tgen_pg_recon_1", 30_000L);
        gateway.givenPayment(new PaymentGatewayInquiryResult(Status.APPROVED, "tgen_pg_recon_1", "ord_pg_recon_1",
                30_000L, DAY.atTime(10, 0), null, null));
        PgChargeReconciliationService service = serviceAtMidnightOf(RUN_DATE);

        int mismatches = service.reconcilePreviousDay(RUN_DATE, JOB);

        assertThat(mismatches).isEqualTo(1);
        assertThat(chargeCount(paymentId)).isEqualTo(1);
        assertThat(chargeAmount(paymentId)).isEqualTo(30_000L);
        MismatchRow row = mismatchRow(paymentId);
        assertThat(row.resolutionType()).isEqualTo("LEDGER_CORRECTION");
        assertThat(row.expected()).isEqualTo(30_000L);
        assertThat(row.actual()).isZero();

        // 같은 영업일로 다시 돌리면 CHARGE 가 이미 있어 보정하지 않는다(멱등)
        int secondRun = service.reconcilePreviousDay(RUN_DATE, JOB);

        assertThat(secondRun).isZero();
        assertThat(chargeCount(paymentId)).isEqualTo(1);
        assertThat(mismatchCount(paymentId)).isEqualTo(1);
    }

    @Test
    void PG_승인_금액이_다르면_미해결_불일치만_남고_재실행해도_한_건이다() {
        long paymentId = approvedPayment("ord_pg_recon_2", "tgen_pg_recon_2", 30_000L);
        gateway.givenPayment(new PaymentGatewayInquiryResult(Status.APPROVED, "tgen_pg_recon_2", "ord_pg_recon_2",
                29_000L, DAY.atTime(10, 0), null, null));
        PgChargeReconciliationService service = serviceAtMidnightOf(RUN_DATE);

        int mismatches = service.reconcilePreviousDay(RUN_DATE, JOB);

        assertThat(mismatches).isEqualTo(1);
        assertThat(chargeCount(paymentId)).isZero();
        MismatchRow row = mismatchRow(paymentId);
        assertThat(row.resolutionType()).isNull();
        assertThat(row.expected()).isEqualTo(29_000L);
        assertThat(row.actual()).isEqualTo(30_000L);

        int secondRun = service.reconcilePreviousDay(RUN_DATE, JOB);

        assertThat(secondRun).isEqualTo(1);
        assertThat(mismatchCount(paymentId)).isEqualTo(1);
    }

    @Test
    void 대사_시각_직전_5분_이내_생성된_결제는_비교에서_빠진다() {
        long paymentId = approvedPayment("ord_pg_recon_3", "tgen_pg_recon_3", 30_000L);
        // 결제 시각을 DAY 23:58 로 다시 넣어 워터마크(23:55) 뒤로 옮긴다
        jdbc.update("update payment set created_at = ? where payment_id = ?", DAY.atTime(23, 58), paymentId);
        gateway.givenPayment(new PaymentGatewayInquiryResult(Status.APPROVED, "tgen_pg_recon_3", "ord_pg_recon_3",
                30_000L, DAY.atTime(23, 58), null, null));
        PgChargeReconciliationService service = serviceAtMidnightOf(RUN_DATE);

        int mismatches = service.reconcilePreviousDay(RUN_DATE, JOB);

        assertThat(mismatches).isEqualTo(1);
        assertThat(chargeCount(paymentId)).isEqualTo(1);
        assertThat(mismatchCount(paymentId)).isEqualTo(1);
    }
}
