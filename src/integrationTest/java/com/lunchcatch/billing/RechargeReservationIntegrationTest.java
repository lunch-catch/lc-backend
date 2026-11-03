package com.lunchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.billing.repository.BudgetDailyPlanRepository;
import com.lunchcatch.billing.repository.PointAccountRepository;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.service.PaymentResultService;
import com.lunchcatch.billing.service.RechargeReservationRetryService;
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
 * 충전 직후 재예약(I-03)을 실제 MySQL 8.4 와 Valkey 9, 캠페인 구현까지 함께 띄워 검증한다.
 * 돈이 걸린 경로라 이 테스트를 둔다.
 *
 * 입구는 재예약 워커가 부르는 RechargeReservationRetryService.reserveDue 다. 승인(PaymentResultService.approve)이
 * 같은 트랜잭션에 재예약 대기 행을 남기고, 워커가 그 행을 집어 재예약한다(#1100).
 *
 * 목으로 확인할 수 없는 것을 본다. 승인 커밋에 대기 행이 함께 남는지, RESERVE 와 계획 행과 캠페인 재개가
 * 한 트랜잭션으로 함께 커밋되는지, 선점이 만료돼 같은 행을 다시 처리해도 결제×캠페인 멱등 키가 중복을 막는지,
 * 충전한 영업일이 지난 행을 재예약하지 않고 닫는지, 커밋 뒤 Valkey 해시의 예약액이 늘어나는지다.
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
    @Autowired RechargeReservationRetryService retryService;
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
            jdbc.update("delete from recharge_reservation_outbox where payment_id = ?", paymentId);
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

    private String outboxStatus(long paymentId) {
        return jdbc.queryForObject(
                "select status from recharge_reservation_outbox where payment_id = ?", String.class, paymentId);
    }

    private String campaignStatus() {
        return jdbc.queryForObject("select status from campaign where campaign_id = ?", String.class, CAMPAIGN);
    }

    @Test
    void 승인은_재예약하지_않고_같은_커밋에_오늘_영업일의_재예약_대기_행을_남긴다() {
        campaignPausedForNoPoints(10000L);
        plan(3000L, 10L);
        long paymentId = requestedPayment(20000L);

        paymentResultService.approve(paymentId, null);

        assertThat(outboxStatus(paymentId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "select business_date from recharge_reservation_outbox where payment_id = ?", LocalDate.class,
                paymentId)).isEqualTo(DAY);
        assertThat(jdbc.queryForObject(
                "select count(*) from point_ledger where entry_type = 'RESERVE' and payment_id = ?",
                Integer.class, paymentId)).isZero();
        assertThat(campaignStatus()).isEqualTo("PAUSED");
    }

    @Test
    void 워커가_대기_행을_집으면_예약과_계획과_캠페인_재개가_함께_커밋되고_행을_닫는다() {
        campaignPausedForNoPoints(10000L);
        plan(3000L, 10L);
        long paymentId = requestedPayment(20000L);
        paymentResultService.approve(paymentId, null);

        retryService.reserveDue();

        assertThat(reservedAmount()).isEqualTo(-7000L);
        assertThat(jdbc.queryForObject(
                "select daily_budget from budget_daily_plan where campaign_id = ? and business_date = ?",
                Long.class, CAMPAIGN, DAY)).isEqualTo(10000L);
        assertThat(campaignStatus()).isEqualTo("ACTIVE");
        assertThat(redis.opsForHash().get("billing:day:" + DAY + ":" + CAMPAIGN, "reserved")).isEqualTo("10000");
        assertThat(outboxStatus(paymentId)).isEqualTo("DONE");
    }

    // 재예약을 커밋한 뒤 행을 닫기 전에 서버가 죽으면 선점이 만료된 뒤 같은 행을 다시 처리한다
    @Test
    void 같은_대기_행을_다시_처리해도_RESERVE는_한_번뿐이다() {
        campaignPausedForNoPoints(10000L);
        plan(3000L, 10L);
        long paymentId = requestedPayment(20000L);
        paymentResultService.approve(paymentId, null);
        paymentResultService.approve(paymentId, null);
        retryService.reserveDue();
        jdbc.update("""
                update recharge_reservation_outbox set status = 'PENDING', next_retry_at = now(6)
                 where payment_id = ?
                """, paymentId);

        retryService.reserveDue();

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

        retryService.reserveDue();

        assertThat(reservedAmount()).isEqualTo(-200L);
        assertThat(campaignStatus()).isEqualTo("PAUSED");
    }

    // 그날 재개할 기회는 지났고 00:00 예약이 그 잔액을 새 날 예산으로 배분한다
    @Test
    void 충전한_영업일이_지난_대기_행은_재예약하지_않고_EXPIRED로_닫는다() {
        campaignPausedForNoPoints(10000L);
        plan(3000L, 10L);
        long paymentId = requestedPayment(20000L);
        paymentResultService.approve(paymentId, null);
        jdbc.update("update recharge_reservation_outbox set business_date = ? where payment_id = ?",
                DAY.minusDays(1), paymentId);

        retryService.reserveDue();

        assertThat(outboxStatus(paymentId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "select count(*) from point_ledger where entry_type = 'RESERVE' and payment_id = ?",
                Integer.class, paymentId)).isZero();
        assertThat(campaignStatus()).isEqualTo("PAUSED");
    }
}
