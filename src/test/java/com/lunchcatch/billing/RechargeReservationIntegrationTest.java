package com.lunchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.billing.repository.BudgetDailyPlanRepository;
import com.lunchcatch.billing.repository.PointAccountRepository;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.service.PaymentResultService;
import com.lunchcatch.campaign.contract.CampaignFundingUpdater;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.global.config.ClockConfig;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 충전 직후 재예약(I-03)의 이벤트 입구를 실제 MySQL 8.4 와 Valkey 9, 캠페인 구현까지 함께 띄워 검증한다.
 * 돈이 걸린 경로라 이 테스트를 둔다.
 *
 * 목으로 확인할 수 없는 것을 본다. 충전 커밋 뒤 RESERVE 와 계획 행, 캠페인 재개가 한 트랜잭션으로 함께
 * 커밋되는지, 결제×캠페인 멱등 키가 같은 결제의 재호출에서 중복을 막는지, 커밋 뒤 Valkey 해시의 예약액이
 * 늘어나는지다.
 *
 * 다른 테스트의 캠페인과 섞이지 않게 먼 미래 영업일 대신, 오늘 영업일과 큰 점주·캠페인 ID 를 쓴다.
 */
class RechargeReservationIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.now(ClockConfig.ZONE);
    private static final long OWNER = 960_000L;
    private static final long CAMPAIGN = 960_001L;
    private static final long STORE = 960_002L;

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PaymentResultService paymentResultService;
    @Autowired CampaignQueryService campaignQuery;
    @Autowired CampaignFundingUpdater fundingUpdater;
    @Autowired PointAccountRepository accountRepository;
    @Autowired PointLedgerRepository ledgerRepository;
    @Autowired BudgetDailyPlanRepository planRepository;

    private final List<Long> paymentIds = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("delete from point_ledger where owner_id = ?", OWNER);
        jdbc.update("delete from point_account where owner_id = ?", OWNER);
        jdbc.update("delete from budget_daily_plan where campaign_id = ?", CAMPAIGN);
        jdbc.update("delete from campaign_status_log where campaign_id = ?", CAMPAIGN);
        jdbc.update("delete from campaign where campaign_id = ?", CAMPAIGN);
        for (Long paymentId : paymentIds) {
            jdbc.update("delete from payment where payment_id = ?", paymentId);
        }
        redis.delete("billing:day:" + DAY + ":" + CAMPAIGN);
    }

    private void campaignPausedForNoPoints(long dailyBudget) {
        jdbc.update("""
                insert into campaign
                    (campaign_id, owner_id, store_id, status, status_changed_at, paused_reason, discount_target_type,
                     discount_type, discount_value, issue_quantity, usable_start_time, usable_end_time, daily_budget,
                     start_date, end_date, created_at, updated_at)
                values (?, ?, ?, 'PAUSED', '2026-01-01 00:00:00', 'NO_POINTS', 'ALL', 'AMOUNT', 1000, 100,
                        '11:00:00', '14:00:00', ?, ?, ?, now(6), now(6))
                """, CAMPAIGN, OWNER, STORE, dailyBudget, DAY.minusDays(30), DAY.plusDays(30));
    }

    private void plan(long dailyBudget, long unitPrice) {
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, ?, ?, true, now(6), now(6))
                """, CAMPAIGN, OWNER, DAY, dailyBudget, unitPrice);
        redis.opsForHash().put("billing:day:" + DAY + ":" + CAMPAIGN, "reserved", String.valueOf(dailyBudget));
    }

    // 승인 시도 직전 상태의 결제다. 승인으로 확정하려면 결제 식별자가 있어야 한다(chk_payment_pg_tid)
    private long requestedPayment(long amount) {
        String orderId = "recharge-reserve-" + OWNER + "-" + paymentIds.size();
        jdbc.update("""
                insert into payment (owner_id, order_id, pg_tid, amount, status, created_at, updated_at)
                values (?, ?, ?, ?, 'REQUESTED', now(6), now(6))
                """, OWNER, orderId, "pg-" + orderId, amount);
        long paymentId = jdbc.queryForObject("select payment_id from payment where order_id = ?", Long.class, orderId);
        paymentIds.add(paymentId);
        return paymentId;
    }

    private long reservedAmount() {
        return jdbc.queryForList("""
                select amount from point_ledger
                 where entry_type = 'RESERVE' and campaign_id = ? and payment_id = ?
                """, Long.class, CAMPAIGN, paymentIds.get(0)).get(0);
    }

    @Test
    void 충전_커밋_뒤_새_트랜잭션에서_예약과_계획과_캠페인_재개가_함께_커밋된다() {
        campaignPausedForNoPoints(10000L);
        plan(3000L, 10L);
        long paymentId = requestedPayment(20000L);

        paymentResultService.approve(paymentId, null);

        assertThat(reservedAmount()).isEqualTo(-7000L);
        assertThat(jdbc.queryForObject(
                "select daily_budget from budget_daily_plan where campaign_id = ? and business_date = ?",
                Long.class, CAMPAIGN, DAY)).isEqualTo(10000L);
        assertThat(jdbc.queryForObject("select status from campaign where campaign_id = ?", String.class, CAMPAIGN))
                .isEqualTo("ACTIVE");
        assertThat(redis.opsForHash().get("billing:day:" + DAY + ":" + CAMPAIGN, "reserved")).isEqualTo("10000");
    }

    @Test
    void 같은_결제의_재예약을_두_번_불러도_RESERVE와_재개_통보는_한_번뿐이다() {
        campaignPausedForNoPoints(10000L);
        plan(3000L, 10L);
        long paymentId = requestedPayment(20000L);
        paymentResultService.approve(paymentId, null);
        paymentResultService.approve(paymentId, null);

        assertThat(jdbc.queryForObject(
                "select count(*) from point_ledger where entry_type = 'RESERVE' and campaign_id = ? and payment_id = ?",
                Integer.class, CAMPAIGN, paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select daily_budget from budget_daily_plan where campaign_id = ? and business_date = ?",
                Long.class, CAMPAIGN, DAY)).isEqualTo(10000L);
    }

    @Test
    void 새_예약액이_단가보다_작으면_예약만_늘리고_캠페인은_그대로_중단이다() {
        campaignPausedForNoPoints(300L);
        plan(0L, 10000L);
        long paymentId = requestedPayment(200L);

        paymentResultService.approve(paymentId, null);

        assertThat(reservedAmount()).isEqualTo(-200L);
        assertThat(jdbc.queryForObject("select status from campaign where campaign_id = ?", String.class, CAMPAIGN))
                .isEqualTo("PAUSED");
    }
}
