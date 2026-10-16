package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.contract.BillingStatsQueryService;
import com.launchcatch.billing.contract.CampaignBillingCount;
import com.launchcatch.billing.contract.CampaignDailyBudget;
import com.launchcatch.billing.contract.DailyLedgerTotals;
import com.launchcatch.billing.contract.OwnerCampaignLedgerSum;
import com.launchcatch.billing.dto.ServeIds;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 분석이 부르는 집계 조회의 쿼리를 실제 MySQL 8.4 에서 실행한다. 입구는 BillingStatsQueryService 다.
 * 다른 테스트와 섞이지 않게 먼 미래 영업일과 큰 점주, 캠페인 ID 를 쓴다. 플랫폼 전체 합은 심기 전후 차이로 본다.
 */
class BillingStatsQueryIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2031, 5, 20);
    private static final String DAY_NOON = "2031-05-20 12:00:00";
    private static final String NEXT_DAY_NOON = "2031-05-21 12:00:00";
    private static final long OWNER = 960_000L;
    private static final long OWNER2 = 960_001L;
    private static final long CAMPAIGN = 960_100L;
    private static final long CAMPAIGN2 = 960_101L;

    @Autowired BillingStatsQueryService stats;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("delete from settlement_mismatch where target_id in (?, ?)", CAMPAIGN, CAMPAIGN2);
        jdbc.update("delete from point_ledger where owner_id in (?, ?)", OWNER, OWNER2);
        jdbc.update("delete from deduction_result where owner_id in (?, ?)", OWNER, OWNER2);
        jdbc.update("delete from budget_daily_plan where owner_id in (?, ?)", OWNER, OWNER2);
        jdbc.update("delete from refund_request where owner_id in (?, ?)", OWNER, OWNER2);
        jdbc.update("delete from payment where owner_id in (?, ?)", OWNER, OWNER2);
    }

    private void ledger(long owner, Long campaign, String type, long amount, String businessDate, String at) {
        boolean adjust = type.equals("ADJUST");
        Long paymentId = type.equals("CHARGE") ? payment(owner) : null;
        Long refundRequestId = type.equals("REFUND") ? refundRequest(owner) : null;
        jdbc.update("""
                insert into point_ledger (owner_id, campaign_id, payment_id, refund_request_id, business_date, entry_type,
                                          source, amount, reason, idempotency_key, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, owner, campaign, paymentId, refundRequestId, businessDate, type,
                adjust || refundRequestId != null ? "MANUAL" : "RECONCILIATION", amount,
                adjust ? "정정" : null, adjust ? UUID.randomUUID().toString() : null, at, at);
    }

    private long payment(long owner) {
        String orderId = "stats-" + UUID.randomUUID();
        jdbc.update("""
                insert into payment (owner_id, order_id, pg_tid, amount, status, created_at, updated_at)
                values (?, ?, ?, 10000, 'REQUESTED', now(6), now(6))
                """, owner, orderId, "pg-" + orderId);
        return jdbc.queryForObject("select payment_id from payment where order_id = ?", Long.class, orderId);
    }

    private long refundRequest(long owner) {
        jdbc.update("""
                insert into refund_request (owner_id, amount, fee, status, created_at, updated_at)
                values (?, 400, 0, 'REQUESTED', now(6), now(6))
                """, owner);
        return jdbc.queryForObject("select max(refund_request_id) from refund_request where owner_id = ?", Long.class, owner);
    }

    private void deduct(long owner, long campaign, LocalDate businessDate, String status, long unitPrice, String at) {
        byte[] serveId = ServeIds.toBytes(UUID.randomUUID());
        jdbc.update("""
                insert into deduction_result
                    (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, serveId, campaign, owner, businessDate, status, unitPrice, at, at);
        if (status.equals("BILLED")) {
            jdbc.update("""
                    insert into point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
                    values (?, ?, ?, 'DEDUCT', 'REALTIME', ?, ?, ?)
                    """, owner, campaign, serveId, -unitPrice, at, at);
        }
    }

    private OwnerCampaignLedgerSum sumOf(long owner, Long campaign) {
        return stats.ledgerSums(DAY).stream()
                .filter(s -> s.ownerId().equals(owner) && Objects.equals(s.campaignId(), campaign))
                .findFirst().orElseThrow();
    }

    @Test
    void 원장_유형별_합계가_영업일_귀속_기준대로_묶인다() {
        ledger(OWNER, CAMPAIGN, "RESERVE", -5000L, "2031-05-20", DAY_NOON);
        ledger(OWNER, CAMPAIGN, "RELEASE", 1200L, "2031-05-20", DAY_NOON);
        deduct(OWNER, CAMPAIGN, DAY, "BILLED", 100L, DAY_NOON);
        deduct(OWNER, CAMPAIGN, DAY, "BILLED", 100L, DAY_NOON);
        // 전날 영업일 판정은 그날 합계에 들어가지 않는다
        deduct(OWNER, CAMPAIGN, DAY.minusDays(1), "BILLED", 100L, DAY_NOON);
        // 다른 날 영업일의 RESERVE 는 빠진다
        ledger(OWNER, CAMPAIGN, "RESERVE", -999L, "2031-05-21", NEXT_DAY_NOON);
        ledger(OWNER, null, "CHARGE", 10000L, null, DAY_NOON);
        ledger(OWNER, null, "REFUND", -400L, null, DAY_NOON);
        ledger(OWNER, CAMPAIGN, "ADJUST", 300L, null, DAY_NOON);
        ledger(OWNER, CAMPAIGN, "ADJUST", -50L, null, DAY_NOON);
        ledger(OWNER, CAMPAIGN, "ADJUST", 77L, null, NEXT_DAY_NOON);

        assertThat(sumOf(OWNER, CAMPAIGN)).isEqualTo(
                new OwnerCampaignLedgerSum(OWNER, CAMPAIGN, 0, -5000, -200, 1200, 0, 300, -50));
        assertThat(sumOf(OWNER, null)).isEqualTo(
                new OwnerCampaignLedgerSum(OWNER, null, 10000, 0, 0, 0, -400, 0, 0));
    }

    @Test
    void 캠페인별_DEDUCT_합은_BILLED_수와_단가의_곱과_같다() {
        for (int i = 0; i < 4; i++) {
            deduct(OWNER, CAMPAIGN, DAY, "BILLED", 250L, DAY_NOON);
        }
        deduct(OWNER, CAMPAIGN, DAY, "NOT_BILLED_BUDGET", 250L, DAY_NOON);
        deduct(OWNER, CAMPAIGN, DAY, "NOT_BILLED_PAUSED", 250L, DAY_NOON);
        deduct(OWNER, CAMPAIGN, DAY, "NOT_BILLED_PAUSED", 250L, DAY_NOON);
        deduct(OWNER2, CAMPAIGN2, DAY, "NOT_BILLED_BUDGET", 100L, DAY_NOON);
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, 5000, 250, false, now(6), now(6))
                """, CAMPAIGN, OWNER, DAY);

        CampaignBillingCount count = stats.billingCounts(DAY).stream()
                .filter(c -> c.campaignId().equals(CAMPAIGN)).findFirst().orElseThrow();
        CampaignDailyBudget budget = stats.dailyBudgets(DAY).stream()
                .filter(b -> b.campaignId().equals(CAMPAIGN)).findFirst().orElseThrow();

        assertThat(count).isEqualTo(new CampaignBillingCount(CAMPAIGN, 4, 1, 2));
        assertThat(budget).isEqualTo(new CampaignDailyBudget(CAMPAIGN, 5000, 250));
        assertThat(-sumOf(OWNER, CAMPAIGN).deductAmount()).isEqualTo(count.billedCount() * budget.unitPrice());
        assertThat(stats.billingCounts(DAY)).contains(new CampaignBillingCount(CAMPAIGN2, 0, 1, 0));
    }

    @Test
    void 날짜_말_잔액과_그날_합계와_불일치_건수는_그날_기록만큼_늘어난다() {
        DailyLedgerTotals before = stats.dailyTotals(DAY);
        ledger(OWNER, null, "CHARGE", 10000L, null, DAY_NOON);
        ledger(OWNER, CAMPAIGN, "RESERVE", -4000L, "2031-05-20", DAY_NOON);
        deduct(OWNER, CAMPAIGN, DAY, "BILLED", 100L, DAY_NOON);
        // RESERVE 는 미소진에 들어가지 않고 다음 날 기록도 그날 말 잔액에 들어가지 않는다
        ledger(OWNER, null, "CHARGE", 555L, null, NEXT_DAY_NOON);
        jdbc.update("""
                insert into settlement_mismatch
                    (check_type, business_date, target_id, expected_value, actual_value, created_at, updated_at)
                values ('CAMPAIGN_SPEND_VS_LEDGER', ?, ?, 100, 90, now(6), now(6))
                """, DAY, CAMPAIGN);

        DailyLedgerTotals after = stats.dailyTotals(DAY);

        assertThat(after.unspentBalance() - before.unspentBalance()).isEqualTo(9900);
        assertThat(after.ledgerSum() - before.ledgerSum()).isEqualTo(9900);
        assertThat(after.mismatchCount() - before.mismatchCount()).isEqualTo(1);
    }

    @Test
    void 원장이_없는_영업일은_모두_비어_있다() {
        LocalDate empty = LocalDate.of(2031, 5, 25);

        assertThat(stats.ledgerSums(empty)).isEmpty();
        assertThat(stats.billingCounts(empty)).isEmpty();
        assertThat(stats.dailyBudgets(empty)).isEmpty();
    }
}
