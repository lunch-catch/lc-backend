package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.billing.dto.ServeIds;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.job.DailyReservationJob;
import com.launchcatch.billing.job.FundingJudgementJob;
import com.launchcatch.billing.job.HourlyTargetJob;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.service.DailyReservationRecorder;
import com.launchcatch.billing.service.PaymentResultService;
import com.launchcatch.billing.service.RefundApprovalRecorder;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 00:00 예약 단계를 실제 MySQL 8.4 와 Valkey 9, 캠페인 구현까지 함께 띄워 검증한다. 돈이 걸린 경로라 이 테스트를 둔다.
 *
 * 목으로 확인할 수 없는 것을 본다. 잔액이 원장 합으로 읽혀 RESERVE 와 계획이 함께 커밋되는지, 재실행이 UNIQUE 에
 * 걸리지 않고 건너뛰는지, 판정이 캠페인 상태와 계획의 중단 표시를 함께 바꾸는지(같은 트랜잭션 이벤트), 보정 RELEASE 가
 * deduction_result 를 거친 DEDUCT 합으로 계산되는지, 같은 점주의 충전 승인과 환불 승인이 예약과 점주 계정 행 잠금에서
 * 줄을 서서 서로의 커밋 뒤 잔액을 읽는지다.
 *
 * 입구는 billing 이 등록한 DailyJob 이다. 단계 순서와 묶음 점유는 운영 스케줄러의 몫이라 여기서는 run 을 차례로 부른다.
 * 다른 테스트의 캠페인과 섞이지 않게 먼 미래 영업일과 큰 점주, 가게 ID 를 쓴다. 커밋을 봐야 하므로 테스트 트랜잭션을 걸지 않는다.
 */
class DailyReservationIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 3, 2);
    private static final LocalDate PREV = LocalDate.of(2030, 3, 1);
    private static final long BASE_ID = 900_000L;

    @Autowired DailyReservationJob reservationJob;
    @Autowired FundingJudgementJob judgementJob;
    @Autowired HourlyTargetJob hourlyTargetJob;
    @Autowired BudgetDailyPlanRepository planRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DailyReservationRecorder recorder;
    @Autowired CampaignQueryService campaignQuery;
    @Autowired PaymentResultService paymentResultService;
    @Autowired RefundApprovalRecorder refundApprovalRecorder;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<Long> campaignIds = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("delete from budget_hourly_target");
        jdbc.update("delete from budget_daily_plan");
        jdbc.update("delete from point_ledger");
        jdbc.update("delete from refund_request where owner_id >= ?", BASE_ID);
        jdbc.update("delete from payment where owner_id >= ?", BASE_ID);
        jdbc.update("delete from deduction_result");
        jdbc.update("delete from point_account");
        jdbc.update("delete from batch_execution_log where job_name = 'BILLING_DAILY_RELEASE' and business_date = ?", PREV);
        jdbc.update("delete from batch_execution_log where job_name = 'BILLING_DAILY_RELEASE' and business_date = ?", PREV.minusDays(1));
        for (Long campaignId : campaignIds) {
            jdbc.update("delete from campaign_status_log where campaign_id = ?", campaignId);
            jdbc.update("delete from campaign where campaign_id = ?", campaignId);
        }
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    /*
     * 점주 잔액을 만든다. CHARGE 는 payment_id 가 payment 를 가리키는 FK(fk_point_ledger_payment)라 결제 행까지
     * 만들어야 해서, FK 가 없는 양수 ADJUST 로 넣는다. 잔액 계산(DEDUCT 를 뺀 원장 합)에는 둘이 같다.
     */
    private void charge(long ownerId, long amount) {
        jdbc.update("""
                insert into point_account (owner_id, created_at, updated_at) values (?, now(6), now(6))
                on duplicate key update owner_id = owner_id
                """, ownerId);
        jdbc.update("""
                insert into point_ledger
                    (owner_id, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
                values (?, 'ADJUST', 'MANUAL', ?, '테스트 잔액', ?, now(6), now(6))
                """, ownerId, amount, "seed-" + ownerId);
    }

    private long campaign(long ownerId, long storeId, String status, String pausedReason, long dailyBudget) {
        jdbc.update("""
                insert into campaign
                    (owner_id, store_id, status, status_changed_at, paused_reason, discount_target_type, discount_type,
                     discount_value, issue_quantity, usable_start_time, usable_end_time, daily_budget,
                     start_date, end_date, created_at, updated_at)
                values (?, ?, ?, '2026-01-01 00:00:00', ?, 'ALL', 'AMOUNT', 1000, 100, '11:00:00', '14:00:00', ?,
                        '2030-01-01', '2030-12-31', now(6), now(6))
                """, ownerId, storeId, status, pausedReason, dailyBudget);
        long campaignId = jdbc.queryForObject("select campaign_id from campaign where store_id = ?", Long.class, storeId);
        campaignIds.add(campaignId);
        return campaignId;
    }

    private void runAllSteps() {
        reservationJob.run(DAY);
        judgementJob.run(DAY);
        hourlyTargetJob.run(DAY);
    }

    private List<Long> reserves(long campaignId) {
        return jdbc.queryForList("""
                select amount from point_ledger
                 where entry_type = 'RESERVE' and campaign_id = ? and business_date = ?
                """, Long.class, campaignId, DAY);
    }

    private Map<String, Object> plan(long campaignId) {
        return jdbc.queryForMap("select daily_budget, paused from budget_daily_plan where campaign_id = ? and business_date = ?",
                campaignId, DAY);
    }

    private List<Long> hourlyTargets(long campaignId) {
        return jdbc.queryForList("""
                select t.target_amount from budget_hourly_target t
                  join budget_daily_plan p on p.budget_daily_plan_id = t.budget_daily_plan_id
                 where p.campaign_id = ? and p.business_date = ?
                 order by t.hour
                """, Long.class, campaignId, DAY);
    }

    private long balance(long ownerId) {
        return jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from point_ledger where owner_id = ? and entry_type <> 'DEDUCT'",
                Long.class, ownerId);
    }

    @Test
    void 예약_판정_목표가_함께_남고_다시_돌려도_중복되지_않는다() {
        long ownerA = BASE_ID + 1;
        long ownerB = BASE_ID + 2;
        charge(ownerA, 15000L);
        charge(ownerB, 5L);
        long a1 = campaign(ownerA, BASE_ID + 101, "ACTIVE", null, 10000L);
        long a2 = campaign(ownerA, BASE_ID + 102, "ACTIVE", null, 10000L);
        long a3 = campaign(ownerA, BASE_ID + 103, "PAUSED", "OWNER", 3000L);
        long b1 = campaign(ownerB, BASE_ID + 201, "ACTIVE", null, 3000L);

        runAllSteps();
        runAllSteps();

        // 잔액 15000 을 캠페인 ID 순으로 채우고, 다시 돌려도 RESERVE 는 캠페인마다 한 줄이다
        assertThat(reserves(a1)).containsExactly(-10000L);
        assertThat(reserves(a2)).containsExactly(-5000L);
        assertThat(reserves(a3)).isEmpty();
        assertThat(reserves(b1)).containsExactly(-5L);
        assertThat(balance(ownerA)).isZero();

        assertThat(plan(a3)).containsEntry("daily_budget", 0L).containsEntry("paused", true);
        // 단가 10 보다 작은 예약이라 NO_POINTS 로 멈추고, 같은 트랜잭션의 상태 변경 이벤트가 계획도 중단으로 바꾼다
        assertThat(plan(b1)).containsEntry("daily_budget", 5L).containsEntry("paused", true);
        assertThat(jdbc.queryForMap("select status, paused_reason from campaign where campaign_id = ?", b1))
                .containsEntry("status", "PAUSED").containsEntry("paused_reason", "NO_POINTS");
        assertThat(jdbc.queryForObject("select status from campaign where campaign_id = ?", String.class, a1))
                .isEqualTo("ACTIVE");

        // 0~23시 24개 목표이고 합은 예약액과 같다(기본 가중치의 합이 100)
        assertThat(hourlyTargets(a1)).containsExactly(200L, 100L, 100L, 100L, 100L, 100L, 100L, 300L, 400L, 500L,
                800L, 1000L, 900L, 700L, 400L, 400L, 400L, 500L, 700L, 800L, 600L, 400L, 200L, 200L);
        assertThat(hourlyTargets(a2)).hasSize(24).startsWith(100L, 50L).endsWith(100L, 100L);
        assertThat(hourlyTargets(a2).stream().mapToLong(Long::longValue).sum()).isEqualTo(5000L);
        assertThat(hourlyTargets(a3)).isEmpty();
        assertThat(hourlyTargets(b1)).isEmpty();

        assertThat(redis.opsForHash().get("billing:day:" + DAY + ":" + a1, "reserved")).isEqualTo("10000");
    }

    @Test
    void 전날_쓰고_남은_예약을_한_번만_해제하고_해제_표시를_남기며_예약은_그_뒤_잔액으로_한다() {
        long owner = BASE_ID + 3;
        long prevCampaign = BASE_ID + 301;
        charge(owner, 10000L);
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, 5000, 1000, false, now(6), now(6))
                """, prevCampaign, owner, PREV);
        jdbc.update("""
                insert into point_ledger (owner_id, campaign_id, business_date, entry_type, source, amount, created_at, updated_at)
                values (?, ?, ?, 'RESERVE', 'RECONCILIATION', -5000, now(6), now(6))
                """, owner, prevCampaign, PREV);
        for (int i = 0; i < 3; i++) {
            byte[] serveId = ServeIds.toBytes(UUID.randomUUID());
            jdbc.update("""
                    insert into deduction_result
                        (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                    values (?, ?, ?, ?, 'BILLED', 1000, now(6), now(6))
                    """, serveId, prevCampaign, owner, PREV);
            jdbc.update("""
                    insert into point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
                    values (?, ?, ?, 'DEDUCT', 'REALTIME', -1000, now(6), now(6))
                    """, owner, prevCampaign, serveId);
        }
        long today = campaign(owner, BASE_ID + 302, "ACTIVE", null, 10000L);

        reservationJob.run(DAY);
        reservationJob.run(DAY);

        assertThat(jdbc.queryForList("""
                select amount from point_ledger where entry_type = 'RELEASE' and campaign_id = ? and business_date = ?
                """, Long.class, prevCampaign, PREV)).containsExactly(2000L);
        // 충전 10000 - 전날 예약 5000 + 해제 2000 = 7000 이 오늘 예약에 쓰인다
        assertThat(reserves(today)).containsExactly(-7000L);
        assertThat(balance(owner)).isZero();
        // 분석 01:00 집계가 전날 원천 확정에 읽는 행이다. 다시 돌려도 한 행이다
        assertThat(jdbc.queryForList("""
                select status from batch_execution_log where job_name = 'BILLING_DAILY_RELEASE' and business_date = ?
                """, String.class, PREV)).containsExactly("SUCCESS");
    }

    /*
     * 남은 해제액이 0 인 날짜(전액 소진)는 RELEASE 를 만들 것이 없어 다시 찾지 않는다. 더 오래된 날짜에 남은 몫이 있으면
     * 이번 예약이 해제하고 그 날짜의 해제 표시도 남긴다.
     */
    @Test
    void 남은_해제액이_0인_날짜는_다시_찾지_않고_오래된_날짜는_해제하며_표시를_남긴다() {
        long owner = BASE_ID + 20;
        long exhausted = BASE_ID + 2001;
        long leftover = BASE_ID + 2002;
        LocalDate older = PREV.minusDays(1);
        charge(owner, 10000L);
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, 3000, 1000, false, now(6), now(6))
                """, exhausted, owner, PREV);
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, 5000, 1000, false, now(6), now(6))
                """, leftover, owner, older);
        for (int i = 0; i < 3; i++) {
            byte[] serveId = ServeIds.toBytes(UUID.randomUUID());
            jdbc.update("""
                    insert into deduction_result
                        (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                    values (?, ?, ?, ?, 'BILLED', 1000, now(6), now(6))
                    """, serveId, exhausted, owner, PREV);
            jdbc.update("""
                    insert into point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
                    values (?, ?, ?, 'DEDUCT', 'REALTIME', -1000, now(6), now(6))
                    """, owner, exhausted, serveId);
        }

        assertThat(planRepository.findUnreleasedBusinessDatesBefore(DAY)).containsExactly(older);

        reservationJob.run(DAY);

        assertThat(jdbc.queryForList("""
                select amount from point_ledger where entry_type = 'RELEASE' and campaign_id = ? and business_date = ?
                """, Long.class, leftover, older)).containsExactly(5000L);
        assertThat(jdbc.queryForList("""
                select 1 from point_ledger where entry_type = 'RELEASE' and campaign_id = ?
                """, Integer.class, exhausted)).isEmpty();
        assertThat(jdbc.queryForList("""
                select status from batch_execution_log where job_name = 'BILLING_DAILY_RELEASE' and business_date = ?
                """, String.class, older)).containsExactly("SUCCESS");
        assertThat(planRepository.findUnreleasedBusinessDatesBefore(DAY)).isEmpty();
    }

    /*
     * 앞선 실행이 계획을 만든 뒤 상태 확인 전에 실패했고, 그 사이 한 캠페인이 관리자 중단으로 예약 대상에서 빠진 경우다.
     * 중단은 계획을 만들기 전에 커밋된 것으로 보고 이벤트 없이 캠페인 행만 바꾼다(그때는 계획 행이 없어 수신이 아무것도
     * 못 한다). 계획은 중단으로 만들어지므로 확인 전에는 과금되지 않고, 재실행이 그날 계획 전부를 확인해 ACTIVE 만 푼다.
     */
    @Test
    void 확인_전에_실패한_계획은_중단으로_남고_재실행이_그날_계획_전부를_확인한다() {
        long owner = BASE_ID + 4;
        charge(owner, 10000L);
        long active = campaign(owner, BASE_ID + 401, "ACTIVE", null, 3000L);
        long paused = campaign(owner, BASE_ID + 402, "ACTIVE", null, 3000L);
        List<CampaignInfo> readBeforePause = campaignQuery.findAllById(List.of(active, paused));

        recorder.reserveForOwner(owner, readBeforePause, DAY, 10L);
        jdbc.update("""
                update campaign set status = 'PAUSED', paused_reason = 'ADMIN', status_changed_at = '2026-01-01 01:00:00'
                 where campaign_id = ?
                """, paused);
        assertThat(plan(active)).containsEntry("paused", true);
        assertThat(plan(paused)).containsEntry("paused", true);

        reservationJob.run(DAY);

        assertThat(plan(active)).containsEntry("paused", false);
        assertThat(plan(paused)).containsEntry("paused", true);
        // 예약은 앞선 실행의 것 하나뿐이다
        assertThat(reserves(active)).containsExactly(-3000L);
        assertThat(reserves(paused)).containsExactly(-3000L);
    }

    // 승인 시도 직전 상태의 결제다. 승인으로 확정하려면 결제 식별자가 있어야 한다(chk_payment_pg_tid)
    private long requestedPayment(long ownerId, long amount) {
        String orderId = "reserve-race-" + ownerId;
        jdbc.update("""
                insert into payment (owner_id, order_id, pg_tid, amount, status, created_at, updated_at)
                values (?, ?, ?, ?, 'REQUESTED', now(6), now(6))
                """, ownerId, orderId, "pg-" + orderId, amount);
        return jdbc.queryForObject("select payment_id from payment where order_id = ?", Long.class, orderId);
    }

    private long refundRequest(long ownerId, long amount) {
        jdbc.update("""
                insert into refund_request (owner_id, amount, fee, status, created_at, updated_at)
                values (?, ?, 0, 'REQUESTED', now(6), now(6))
                """, ownerId, amount);
        return jdbc.queryForObject("select refund_request_id from refund_request where owner_id = ?", Long.class, ownerId);
    }

    /*
     * work 를 트랜잭션 하나에서 돌린 뒤 커밋하기 전에 held 를 내리고 release 를 기다린다. 그동안 work 가 잡은 점주 계정 행
     * 잠금을 쥐고 있다. work 가 부르는 서비스의 @Transactional 은 이 트랜잭션에 합류하므로 커밋 시점을 테스트가 정한다.
     */
    private Future<?> holdInTransaction(ExecutorService pool, Runnable work, CountDownLatch held, CountDownLatch release) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return pool.submit(() -> transaction.executeWithoutResult(status -> {
            work.run();
            held.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release 를 기다리다 시간이 지났다");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
    }

    /*
     * 충전 승인(PaymentResultService.approve)이 점주 계정 행을 쥔 채 커밋하지 않은 동안 예약이 시작되는 경우다.
     * 예약은 계정 행 잠금에서 기다렸다가 충전이 커밋된 뒤의 잔액으로 배분해야 한다. 잠금 없이 잔액을 먼저 읽으면 충전
     * 전 잔액(10000)으로 예약한다.
     */
    @Test
    void 충전_승인이_계정_행을_쥐고_있으면_예약은_기다렸다가_충전_뒤_잔액으로_예약한다() throws Exception {
        long owner = BASE_ID + 5;
        charge(owner, 10000L);
        long campaignId = campaign(owner, BASE_ID + 501, "ACTIVE", null, 30000L);
        long paymentId = requestedPayment(owner, 5000L);
        List<CampaignInfo> campaigns = campaignQuery.findAllById(List.of(campaignId));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> charging = holdInTransaction(pool, () -> paymentResultService.approve(paymentId, null),
                    held, release);
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> reserving = pool.submit(() -> recorder.reserveForOwner(owner, campaigns, DAY, 10L));
            // 충전이 커밋되기 전에는 예약이 계정 행 잠금에서 기다린다
            assertThatThrownBy(() -> reserving.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            charging.get(10, TimeUnit.SECONDS);
            reserving.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(reserves(campaignId)).containsExactly(-15000L);
        assertThat(balance(owner)).isZero();
    }

    /*
     * 예약이 점주 계정 행을 쥔 채 커밋하지 않은 동안 같은 점주의 환불 승인(RefundApprovalRecorder.approve)이 들어오는
     * 경우다. 예약이 캠페인을 읽은 뒤 캠페인이 끝나 환불 승인의 집행 중 캠페인 확인은 통과한다. 환불 승인은 잠금에서
     * 기다렸다가 예약 뒤 잔액(7000)이 요청 금액(10000)보다 적으므로 승인하지 않아야 한다(BILLING-013). 잠금 없이
     * 잔액을 먼저 읽으면 예약과 환불이 모두 기록되어 잔액이 음수가 된다.
     */
    @Test
    void 예약이_계정_행을_쥐고_있으면_환불_승인은_기다렸다가_예약_뒤_잔액으로_판단한다() throws Exception {
        long owner = BASE_ID + 6;
        charge(owner, 10000L);
        long campaignId = campaign(owner, BASE_ID + 601, "ACTIVE", null, 3000L);
        List<CampaignInfo> campaigns = campaignQuery.findAllById(List.of(campaignId));
        jdbc.update("update campaign set status = 'ENDED', status_changed_at = '2026-01-01 01:00:00' where campaign_id = ?",
                campaignId);
        long refundRequestId = refundRequest(owner, 10000L);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reserving = holdInTransaction(pool,
                    () -> recorder.reserveForOwner(owner, campaigns, DAY, 10L), held, release);
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> refunding = pool.submit(() -> refundApprovalRecorder.approve(1L, refundRequestId, owner));
            // 예약이 커밋되기 전에는 환불 승인이 계정 행 잠금에서 기다린다
            assertThatThrownBy(() -> refunding.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            reserving.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> refunding.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(BillingException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.INSUFFICIENT_BALANCE));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(reserves(campaignId)).containsExactly(-3000L);
        assertThat(balance(owner)).isEqualTo(7000L);
        assertThat(jdbc.queryForObject("select status from refund_request where refund_request_id = ?",
                String.class, refundRequestId)).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("select count(*) from point_ledger where owner_id = ? and entry_type = 'REFUND'",
                Integer.class, owner)).isZero();
    }
}
