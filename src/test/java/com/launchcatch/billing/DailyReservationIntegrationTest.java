package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.dto.ServeIds;
import com.launchcatch.billing.job.DailyReservationJob;
import com.launchcatch.billing.job.FundingJudgementJob;
import com.launchcatch.billing.job.HourlyTargetJob;
import com.launchcatch.billing.service.DailyReservationRecorder;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 00:00 예약 단계를 실제 MySQL 8.4 와 Valkey 9, 캠페인 구현까지 함께 띄워 검증한다. 돈이 걸린 경로라 이 테스트를 둔다.
 *
 * 목으로 확인할 수 없는 것을 본다. 잔액이 원장 합으로 읽혀 RESERVE 와 계획이 함께 커밋되는지, 재실행이 UNIQUE 에
 * 걸리지 않고 건너뛰는지, 판정이 캠페인 상태와 계획의 중단 표시를 함께 바꾸는지(같은 트랜잭션 이벤트), 보정 RELEASE 가
 * deduction_result 를 거친 DEDUCT 합으로 계산되는지다.
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
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DailyReservationRecorder recorder;
    @Autowired CampaignQueryService campaignQuery;

    private final List<Long> campaignIds = new ArrayList<>();

    @AfterEach
    void clean() {
        jdbc.update("delete from budget_hourly_target");
        jdbc.update("delete from budget_daily_plan");
        jdbc.update("delete from point_ledger");
        jdbc.update("delete from deduction_result");
        jdbc.update("delete from point_account");
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

        assertThat(hourlyTargets(a1)).containsExactly(3400L, 3300L, 3300L);
        assertThat(hourlyTargets(a2)).containsExactly(1700L, 1650L, 1650L);
        assertThat(hourlyTargets(a3)).isEmpty();
        assertThat(hourlyTargets(b1)).isEmpty();

        assertThat(redis.opsForHash().get("billing:day:" + DAY + ":" + a1, "reserved")).isEqualTo("10000");
    }

    @Test
    void 전날_쓰고_남은_예약을_한_번만_보정_해제하고_예약은_그_뒤_잔액으로_한다() {
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
        // 충전 10000 - 전날 예약 5000 + 보정 해제 2000 = 7000 이 오늘 예약에 쓰인다
        assertThat(reserves(today)).containsExactly(-7000L);
        assertThat(balance(owner)).isZero();
    }

    /*
     * 예약이 캠페인을 읽은 뒤 계획을 만들기 전에 중단이 커밋된 경우다. 그때 계획 행이 없어 상태 변경 수신은 아무것도 못
     * 하므로 여기서는 이벤트 없이 캠페인 행만 바꿔 같은 상황을 만든다. 예약이 낡은 상태로 계획을 만들어도, 커밋 뒤의
     * 상태 재확인이 중단으로 맞춰야 중단된 캠페인에 과금되지 않는다.
     */
    @Test
    void 예약이_읽은_뒤에_중단된_캠페인은_커밋_뒤_재확인이_계획을_중단으로_맞춘다() {
        long owner = BASE_ID + 4;
        charge(owner, 10000L);
        long campaignId = campaign(owner, BASE_ID + 401, "ACTIVE", null, 3000L);
        List<CampaignInfo> readBeforePause = campaignQuery.findAllById(List.of(campaignId));
        jdbc.update("""
                update campaign set status = 'PAUSED', paused_reason = 'OWNER', status_changed_at = '2026-01-01 01:00:00'
                 where campaign_id = ?
                """, campaignId);

        recorder.reserveForOwner(owner, readBeforePause, DAY, 10L);
        assertThat(plan(campaignId)).containsEntry("paused", false);

        assertThat(recorder.resyncStatus(List.of(campaignId), DAY)).isEqualTo(1);
        assertThat(plan(campaignId)).containsEntry("paused", true);
        // 다시 맞춰도 바뀌는 것이 없다
        assertThat(recorder.resyncStatus(List.of(campaignId), DAY)).isZero();
    }
}
