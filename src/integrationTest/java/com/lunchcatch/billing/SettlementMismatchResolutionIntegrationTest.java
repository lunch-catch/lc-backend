package com.lunchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.billing.entity.LedgerSource;
import com.lunchcatch.billing.entity.PointLedger;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/*
 * 관리자가 대사 불일치를 닫는 두 입구를 실제 MySQL 8.4 로 검증한다.
 * 목으로 확인할 수 없는 것을 본다. 조정 원장과 대사 건의 해결 상태가 한 트랜잭션으로 함께 커밋되거나 함께 빠지는지,
 * V11 이 넓힌 CHECK 가 새 해결 값과 관리자 ID 를 받는지다.
 */
class SettlementMismatchResolutionIntegrationTest extends AbstractBillingIntegrationTest {

    private static final long ADMIN = 950_701L;
    private static final LocalDate DAY = LocalDate.of(2030, 4, 10);
    private static final LocalDateTime DETECTED = DAY.plusDays(1).atTime(1, 31);
    private static final CustomUserDetails ADMIN_USER = new CustomUserDetails(ADMIN, Role.ADMIN);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PointLedgerRepository ledgerRepository;

    private long owner;
    private String prefix;

    @BeforeEach
    void 점주를_만든다() {
        prefix = UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = DAY.atTime(9, 0);
        jdbc.update("insert into owner (email, password_hash, created_at, updated_at) values (?, ?, ?, ?)",
                prefix + "@mismatch.test", "hash", now, now);
        owner = jdbc.queryForObject("select owner_id from owner where email = ?", Long.class, prefix + "@mismatch.test");
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from settlement_mismatch where target_id in"
                + " (select payment_id from payment where owner_id = ?) and check_type = 'PG_VS_CHARGE'", owner);
        jdbc.update("delete from settlement_mismatch where check_type = 'CAMPAIGN_SPEND_VS_LEDGER' and target_id = ?",
                owner);
        jdbc.update("delete from point_ledger where owner_id = ?", owner);
        jdbc.update("delete from point_account where owner_id = ?", owner);
        jdbc.update("delete from payment where owner_id = ?", owner);
        jdbc.update("delete from owner where owner_id = ?", owner);
    }

    // 결제와 그 결제의 CHARGE 를 넣어 점주 잔액을 charge 만큼 만든다
    private long paymentCharged(long charge) {
        String orderId = "ord_" + prefix;
        jdbc.update("""
                insert into payment
                    (owner_id, order_id, pg_tid, amount, status, approved_at, created_at, updated_at)
                values (?, ?, ?, ?, 'APPROVED', ?, ?, ?)
                """, owner, orderId, "tid_" + prefix, charge, DAY.atTime(10, 0), DAY.atTime(10, 0), DAY.atTime(10, 0));
        long paymentId = jdbc.queryForObject("select payment_id from payment where order_id = ?", Long.class, orderId);
        ledgerRepository.save(PointLedger.charge(owner, paymentId, charge, LedgerSource.REALTIME));
        return paymentId;
    }

    private long openMismatch(String checkType, long targetId, long expected, long actual) {
        jdbc.update("""
                insert into settlement_mismatch
                    (check_type, business_date, target_id, expected_value, actual_value, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, checkType, DAY, targetId, expected, actual, DETECTED, DETECTED);
        return jdbc.queryForObject("select settlement_mismatch_id from settlement_mismatch"
                + " where check_type = ? and target_id = ? and business_date = ?", Long.class, checkType, targetId, DAY);
    }

    private ResultActions adjust(long amount, long mismatchId) throws Exception {
        String body = """
                {"requestId": "%s", "ownerId": %d, "amount": %d, "reason": "PG 미승인 결제 회수", "mismatchId": %d}
                """.formatted(UUID.randomUUID(), owner, amount, mismatchId);
        return mvc.perform(post("/v1/admin/point-adjustments").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(user(ADMIN_USER)));
    }

    private ResultActions close(long mismatchId) throws Exception {
        return mvc.perform(post("/v1/admin/settlement-mismatches/" + mismatchId + ":closeWithoutAdjustment")
                .with(user(ADMIN_USER)));
    }

    private String resolutionType(long mismatchId) {
        return jdbc.queryForObject("select resolution_type from settlement_mismatch where settlement_mismatch_id = ?",
                String.class, mismatchId);
    }

    private Long resolvedBy(long mismatchId) {
        return jdbc.queryForObject("select resolved_by from settlement_mismatch where settlement_mismatch_id = ?",
                Long.class, mismatchId);
    }

    private int adjustCount() {
        return jdbc.queryForObject("select count(*) from point_ledger where owner_id = ? and entry_type = 'ADJUST'",
                Integer.class, owner);
    }

    @Test
    void PG_미승인_결제의_CHARGE를_음수로_회수하면_원장과_해결_상태가_함께_기록된다() throws Exception {
        long paymentId = paymentCharged(10_000L);
        long mismatchId = openMismatch("PG_VS_CHARGE", paymentId, 0L, 10_000L);

        adjust(-10_000L, mismatchId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amount").value(-10_000));

        assertThat(adjustCount()).isEqualTo(1);
        assertThat(resolutionType(mismatchId)).isEqualTo("MANUAL_ADJUSTMENT");
        assertThat(resolvedBy(mismatchId)).isEqualTo(ADMIN);
        assertThat(ledgerRepository.sumBalance(owner)).isZero();
    }

    @Test
    void PG_건의_정정액과_다른_금액은_거절하고_원장도_해결_상태도_남기지_않는다() throws Exception {
        long paymentId = paymentCharged(10_000L);
        // 정정액은 9900 - 10000 = -100 이다
        long mismatchId = openMismatch("PG_VS_CHARGE", paymentId, 9_900L, 10_000L);

        adjust(-500L, mismatchId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));

        assertThat(adjustCount()).isZero();
        assertThat(resolutionType(mismatchId)).isNull();
    }

    @Test
    void PG_건의_음수_조정이_잔액을_넘으면_BILLING_016이고_원장도_해결_상태도_남기지_않는다() throws Exception {
        long paymentId = paymentCharged(10_000L);
        long mismatchId = openMismatch("PG_VS_CHARGE", paymentId, 0L, 10_000L);
        ledgerRepository.save(PointLedger.adjust(owner, -1L, "선행 차감", null, null, "pre-" + prefix));

        adjust(-10_000L, mismatchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING-016"));

        assertThat(adjustCount()).isEqualTo(1);
        assertThat(resolutionType(mismatchId)).isNull();
    }

    @Test
    void 카운터_대_원장_건을_조정_없이_닫으면_원장은_그대로이고_다시_닫으면_거절한다() throws Exception {
        // 대상 캠페인을 읽지 않는 경로라 점주 ID 를 캠페인 ID 자리에 써서 다른 테스트의 행과 겹치지 않게 한다
        long mismatchId = openMismatch("CAMPAIGN_SPEND_VS_LEDGER", owner, 1_000L, 1_100L);

        close(mismatchId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mismatchId").value(mismatchId))
                .andExpect(jsonPath("$.data.resolutionType").value("NO_ADJUSTMENT_REQUIRED"))
                .andExpect(jsonPath("$.data.resolvedBy").value(ADMIN));

        assertThat(resolutionType(mismatchId)).isEqualTo("NO_ADJUSTMENT_REQUIRED");
        assertThat(resolvedBy(mismatchId)).isEqualTo(ADMIN);
        assertThat(jdbc.queryForObject("select count(*) from point_ledger where owner_id = ?", Integer.class, owner))
                .isZero();

        close(mismatchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING-021"));
    }

    @Test
    void 점주_토큰으로는_조정_없이_닫을_수_없다() throws Exception {
        long mismatchId = openMismatch("CAMPAIGN_SPEND_VS_LEDGER", owner, 1_100L, 1_000L);

        mvc.perform(post("/v1/admin/settlement-mismatches/" + mismatchId + ":closeWithoutAdjustment")
                        .with(user(new CustomUserDetails(owner, Role.OWNER))))
                .andExpect(status().isForbidden());

        assertThat(resolutionType(mismatchId)).isNull();
    }
}
