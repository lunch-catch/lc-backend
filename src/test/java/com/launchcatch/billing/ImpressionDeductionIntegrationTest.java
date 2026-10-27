package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.billing.contract.DeductionOutcome;
import com.launchcatch.billing.contract.DeductionRequest;
import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.service.DailyPlanSnapshotService;
import com.launchcatch.global.config.ClockConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 노출 차감을 실제 MySQL 8.4 와 Valkey 9 로 검증한다. 돈이 걸린 경로라 이 테스트 하나만 둔다.
 *
 * 목으로 확인할 수 없는 것을 본다. 판정 Lua 가 동시 요청에서 예약액을 넘기지 않는지, 같은 serveId 의 재호출이
 * 행과 소진을 한 번만 남기는지, 무변경 갱신 INSERT 가 실제로 중복을 흡수하는지, 롤백이 두 INSERT 를 함께
 * 되돌리는지, 해시가 사라졌을 때 재구성이 한 번만 일어나고 소진이 원장과 맞는지다.
 *
 * 커밋을 봐야 하므로 테스트 트랜잭션을 걸지 않고 TransactionTemplate 으로 부른다. 매번 행과 키를 지운다.
 * 그날 계획의 캐시가 테스트 사이에 남으므로 테스트마다 다른 캠페인 ID 를 쓴다.
 */
@Import(ImpressionDeductionIntegrationTest.TimeConfig.class)
class ImpressionDeductionIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Long OWNER = 7L;
    private static final long UNIT = 10L;
    private static final AtomicLong CAMPAIGN_SEQ = new AtomicLong(1000);
    private static final long NO_PLAN_STORE = 970_001L;

    @Autowired PointDeductor deductor;
    @Autowired BudgetQueryService budgetQuery;
    @Autowired BudgetDailyPlanRepository planRepository;
    @Autowired DailyPlanSnapshotService snapshotService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meterRegistry;

    @AfterEach
    void clean() {
        jdbc.update("delete from point_ledger");
        jdbc.update("delete from deduction_result");
        jdbc.update("delete from budget_daily_plan");
        jdbc.update("delete from point_account where owner_id = ?", OWNER);
        jdbc.update("delete from campaign where store_id = ?", NO_PLAN_STORE);
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    private long plan(long unitsOfBudget, boolean paused) {
        long campaignId = CAMPAIGN_SEQ.incrementAndGet();
        jdbc.update("""
                insert into point_account (owner_id, created_at, updated_at)
                values (?, now(6), now(6))
                on duplicate key update owner_id = owner_id
                """, OWNER);
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, now(6), now(6))
                """, campaignId, OWNER, DAY, unitsOfBudget * UNIT, UNIT, paused);
        return campaignId;
    }

    private DeductionStatus deductInTx(UUID serveId, long campaignId) {
        return tx.execute(status -> deductor.deduct(serveId, campaignId, DAY));
    }

    private List<DeductionStatus> deductConcurrently(List<UUID> serveIds, long campaignId) throws Exception {
        // Hikari 풀(maximum-pool-size 8)을 넘지 않게 둔다. 넘으면 남는 스레드가 커넥션을 기다리다 connection-timeout 에 깨진다
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(serveIds.size(), 8));
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<DeductionStatus>> futures = new ArrayList<>();
            for (UUID serveId : serveIds) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return deductInTx(serveId, campaignId);
                }));
            }
            start.countDown();
            List<DeductionStatus> results = new ArrayList<>();
            for (Future<DeductionStatus> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count(DeductionStatus status, List<DeductionStatus> results) {
        return results.stream().filter(result -> result == status).count();
    }

    private long ledgerDeductSum(long campaignId) {
        return jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from point_ledger where entry_type = 'DEDUCT' and campaign_id = ?",
                Long.class, campaignId);
    }

    private long rows(String table, long campaignId) {
        return jdbc.queryForObject("select count(*) from " + table + " where campaign_id = ?", Long.class, campaignId);
    }

    private long spendInValkey(long campaignId) {
        Object spend = redis.opsForHash().get("billing:day:" + DAY + ":" + campaignId, "spend");
        return Long.parseLong((String) spend);
    }

    private static List<UUID> serveIds(int size) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    @Test
    void 같은_캠페인에_동시_20건이_와도_예약액을_넘기지_않고_소진이_원장과_맞는다() throws Exception {
        long campaignId = plan(10, false);

        List<DeductionStatus> results = deductConcurrently(serveIds(20), campaignId);

        assertThat(count(DeductionStatus.BILLED, results)).isEqualTo(10);
        assertThat(count(DeductionStatus.NOT_BILLED_BUDGET, results)).isEqualTo(10);
        assertThat(rows("deduction_result", campaignId)).isEqualTo(20);
        assertThat(rows("point_ledger", campaignId)).isEqualTo(10);
        assertThat(ledgerDeductSum(campaignId)).isEqualTo(-10 * UNIT);
        assertThat(spendInValkey(campaignId)).isEqualTo(10 * UNIT);
    }

    @Test
    void 같은_serveId를_순차로_두_번_부르면_첫_판정을_돌려주고_행과_소진은_한_번이다() {
        long campaignId = plan(30, false);
        UUID serveId = UUID.randomUUID();

        DeductionStatus first = deductInTx(serveId, campaignId);
        DeductionStatus second = deductInTx(serveId, campaignId);

        assertThat(first).isEqualTo(DeductionStatus.BILLED);
        assertThat(second).isEqualTo(first);
        assertThat(rows("deduction_result", campaignId)).isEqualTo(1);
        assertThat(rows("point_ledger", campaignId)).isEqualTo(1);
        assertThat(spendInValkey(campaignId)).isEqualTo(UNIT);
    }

    @Test
    void 처리_표시가_사라진_뒤_같은_serveId를_부르면_저장된_처음_판정을_돌려주고_원장은_늘지_않는다() {
        long campaignId = plan(1, false);
        UUID billed = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        assertThat(deductInTx(billed, campaignId)).isEqualTo(DeductionStatus.BILLED);
        assertThat(deductInTx(rejected, campaignId)).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        // 처리 표시가 사라지고 예산이 넉넉해져 Lua 가 다시 판정하면 다른 값이 나오는 상황을 만든다
        redis.delete(List.of("billing:served:" + rejected, "billing:served:" + billed));
        redis.opsForHash().put("billing:day:" + DAY + ":" + campaignId, "reserved", String.valueOf(100 * UNIT));

        assertThat(deductInTx(rejected, campaignId)).isEqualTo(DeductionStatus.NOT_BILLED_BUDGET);
        assertThat(deductInTx(billed, campaignId)).isEqualTo(DeductionStatus.BILLED);

        assertThat(rows("deduction_result", campaignId)).isEqualTo(2);
        assertThat(rows("point_ledger", campaignId)).isEqualTo(1);
        assertThat(ledgerDeductSum(campaignId)).isEqualTo(-UNIT);
    }

    @Test
    void 해시가_모두_사라진_뒤_진행률_한_번의_조회가_원장_합으로_재구성한다() {
        long first = plan(30, false);
        long second = plan(30, false);
        long third = plan(30, false);
        for (int i = 0; i < 3; i++) {
            deductInTx(UUID.randomUUID(), first);
        }
        for (int i = 0; i < 5; i++) {
            deductInTx(UUID.randomUUID(), second);
        }
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        double rebuildBefore = meterRegistry.counter("billing_spend_hash_rebuild_total").count();

        List<BudgetProgress> progress = budgetQuery.getProgress(
                List.of(first, second, third), DAY);

        assertThat(progress).extracting(BudgetProgress::todaySpend).containsExactly(3 * UNIT, 5 * UNIT, 0L);
        assertThat(meterRegistry.counter("billing_spend_hash_rebuild_total").count() - rebuildBefore).isEqualTo(3.0);
    }

    @Test
    void 같은_serveId를_동시에_불러도_행과_소진은_한_번이다() throws Exception {
        long campaignId = plan(30, false);
        UUID serveId = UUID.randomUUID();

        List<DeductionStatus> results = deductConcurrently(List.of(serveId, serveId, serveId, serveId, serveId), campaignId);

        assertThat(results).containsOnly(DeductionStatus.BILLED);
        assertThat(rows("deduction_result", campaignId)).isEqualTo(1);
        assertThat(rows("point_ledger", campaignId)).isEqualTo(1);
        assertThat(spendInValkey(campaignId)).isEqualTo(UNIT);
    }

    @Test
    void 롤백하면_판정_행과_원장_행이_함께_사라지고_재호출이_다시_남긴다() {
        long campaignId = plan(30, false);
        UUID serveId = UUID.randomUUID();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            deductor.deduct(serveId, campaignId, DAY);
            throw new IllegalStateException("노출 저장 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows("deduction_result", campaignId)).isZero();
        assertThat(rows("point_ledger", campaignId)).isZero();
        // Valkey 도 함께 돌아간다. 소진은 원래대로이고 처리 표시는 지워져 재호출이 새로 판정한다
        assertThat(spendInValkey(campaignId)).isZero();
        assertThat(redis.hasKey("billing:served:" + serveId)).isFalse();

        assertThat(deductInTx(serveId, campaignId)).isEqualTo(DeductionStatus.BILLED);
        assertThat(rows("deduction_result", campaignId)).isEqualTo(1);
        assertThat(rows("point_ledger", campaignId)).isEqualTo(1);
        assertThat(spendInValkey(campaignId)).isEqualTo(UNIT);
    }

    @Test
    void 롤백된_노출이_예산을_차지하지_않아_다음_노출이_과금된다() {
        long campaignId = plan(1, false);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(deductor.deduct(UUID.randomUUID(), campaignId, DAY)).isEqualTo(DeductionStatus.BILLED);
            throw new IllegalStateException("노출 저장 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(deductInTx(UUID.randomUUID(), campaignId)).isEqualTo(DeductionStatus.BILLED);
        assertThat(spendInValkey(campaignId)).isEqualTo(UNIT);
        assertThat(ledgerDeductSum(campaignId)).isEqualTo(-UNIT);
    }

    @Test
    void 이미_커밋된_노출의_재호출이_롤백돼도_처음_노출의_소진과_표시는_그대로다() {
        long campaignId = plan(30, false);
        UUID serveId = UUID.randomUUID();
        assertThat(deductInTx(serveId, campaignId)).isEqualTo(DeductionStatus.BILLED);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(deductor.deduct(serveId, campaignId, DAY)).isEqualTo(DeductionStatus.BILLED);
            throw new IllegalStateException("재호출 저장 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(spendInValkey(campaignId)).isEqualTo(UNIT);
        assertThat(redis.hasKey("billing:served:" + serveId)).isTrue();
        assertThat(rows("point_ledger", campaignId)).isEqualTo(1);
    }

    @Test
    void 트랜잭션_밖에서_부르면_정산이_트랜잭션을_시작해_과금을_커밋한다() {
        long campaignId = plan(30, false);

        assertThat(deductor.deduct(UUID.randomUUID(), campaignId, DAY)).isEqualTo(DeductionStatus.BILLED);
        assertThat(ledgerDeductSum(campaignId)).isEqualTo(-UNIT);
        assertThat(rows("deduction_result", campaignId)).isEqualTo(1);
    }

    @Test
    void 묶음_차감은_목록을_한_번에_판정하고_예약액을_넘기지_않는다() {
        long campaignId = plan(3, false);
        List<UUID> ids = serveIds(5);

        List<DeductionOutcome> outcomes = deductor.deductAll(
                ids.stream().map(id -> new DeductionRequest(id, campaignId, DAY)).toList());

        assertThat(outcomes).extracting(DeductionOutcome::serveId).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == DeductionStatus.BILLED).hasSize(3);
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == DeductionStatus.NOT_BILLED_BUDGET).hasSize(2);
        assertThat(ledgerDeductSum(campaignId)).isEqualTo(-3 * UNIT);
        assertThat(rows("deduction_result", campaignId)).isEqualTo(5);
        assertThat(spendInValkey(campaignId)).isEqualTo(3 * UNIT);
    }

    // 스냅샷은 남았는데 계획 행이 사라진 점주의 묶음은 계획 잠금에서 실패한다. 그 묶음만 실패로 돌아오고 다른 점주의 과금은 커밋된다
    @Test
    void 묶음_차감에서_한_점주_묶음이_실패해도_다른_점주_묶음은_커밋된다() {
        long healthy = plan(30, false);
        long broken = CAMPAIGN_SEQ.incrementAndGet();
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, ?, ?, false, now(6), now(6))
                """, broken, 970_002L, DAY, 30 * UNIT, UNIT);
        snapshotService.find(DAY, broken);
        jdbc.update("delete from budget_daily_plan where campaign_id = ?", broken);
        UUID healthyServe = UUID.randomUUID();
        UUID brokenServe = UUID.randomUUID();

        List<DeductionOutcome> outcomes = deductor.deductAll(List.of(
                new DeductionRequest(brokenServe, broken, DAY),
                new DeductionRequest(healthyServe, healthy, DAY)));

        assertThat(outcomes).containsExactlyInAnyOrder(
                DeductionOutcome.failed(brokenServe),
                DeductionOutcome.judged(healthyServe, DeductionStatus.BILLED));
        assertThat(ledgerDeductSum(healthy)).isEqualTo(-UNIT);
        assertThat(rows("deduction_result", broken)).isZero();
        assertThat(rows("point_ledger", broken)).isZero();
    }

    @Test
    void 묶음_차감을_트랜잭션_안에서_부르면_거절하고_아무것도_남기지_않는다() {
        long campaignId = plan(30, false);

        assertThatThrownBy(() -> tx.execute(status ->
                deductor.deductAll(List.of(new DeductionRequest(UUID.randomUUID(), campaignId, DAY)))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rows("deduction_result", campaignId)).isZero();
    }

    @Test
    void 해시가_사라진_뒤_동시에_불러도_재구성은_한_번이고_소진이_이어진다() throws Exception {
        long campaignId = plan(10, false);
        for (int i = 0; i < 5; i++) {
            deductInTx(UUID.randomUUID(), campaignId);
        }
        redis.delete("billing:day:" + DAY + ":" + campaignId);
        double rebuildBefore = meterRegistry.counter("billing_spend_hash_rebuild_total").count();

        List<DeductionStatus> results = deductConcurrently(serveIds(20), campaignId);

        double rebuilds = meterRegistry.counter("billing_spend_hash_rebuild_total").count() - rebuildBefore;
        assertThat(rebuilds).isEqualTo(1.0);
        assertThat(count(DeductionStatus.BILLED, results)).isEqualTo(5);
        assertThat(count(DeductionStatus.NOT_BILLED_BUDGET, results)).isEqualTo(15);
        assertThat(ledgerDeductSum(campaignId)).isEqualTo(-10 * UNIT);
        assertThat(spendInValkey(campaignId)).isEqualTo(10 * UNIT);
    }

    /*
     * 00:00 예약 전이거나 전날 집행이 끝난 캠페인처럼 그날 계획이 없는 경우다. 캠페인의 점주와 운영 단가(ops V3 의 10)로
     * 판정 행을 남겨 광고 서빙의 유효 노출 수와 판정 합이 맞게 한다. 과금하지 않아 원장과 Valkey 는 그대로다.
     */
    @Test
    void 그날_계획이_없으면_계획_없음_판정만_남기고_원장과_Valkey는_그대로다() {
        long campaignId = endedCampaignWithoutPlan();

        DeductionStatus status = deductInTx(UUID.randomUUID(), campaignId);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_NO_PLAN);
        assertThat(jdbc.queryForMap(
                "select owner_id, billing_status, unit_price from deduction_result where campaign_id = ?", campaignId))
                .containsEntry("owner_id", OWNER)
                .containsEntry("billing_status", "NOT_BILLED_NO_PLAN")
                .containsEntry("unit_price", 10L);
        assertThat(rows("point_ledger", campaignId)).isZero();
        assertThat(redis.hasKey("billing:day:" + DAY + ":" + campaignId)).isFalse();
    }

    private long endedCampaignWithoutPlan() {
        jdbc.update("""
                insert into campaign
                    (owner_id, store_id, status, status_changed_at, discount_target_type, discount_type,
                     discount_value, issue_quantity, usable_start_time, usable_end_time, daily_budget,
                     start_date, end_date, created_at, updated_at)
                values (?, ?, 'ENDED', '2026-01-01 00:00:00', 'ALL', 'AMOUNT', 1000, 100, '11:00:00', '14:00:00', 10000,
                        '2026-10-01', '2026-10-13', now(6), now(6))
                """, OWNER, NO_PLAN_STORE);
        return jdbc.queryForObject("select campaign_id from campaign where store_id = ?", Long.class, NO_PLAN_STORE);
    }

    @Test
    void 중단된_캠페인은_과금하지_않고_원장도_남기지_않는다() {
        long campaignId = plan(30, true);

        DeductionStatus status = deductInTx(UUID.randomUUID(), campaignId);

        assertThat(status).isEqualTo(DeductionStatus.NOT_BILLED_PAUSED);
        assertThat(rows("deduction_result", campaignId)).isEqualTo(1);
        assertThat(rows("point_ledger", campaignId)).isZero();
    }

    @Test
    void 재구성이_계획_행을_잠그고_있는_동안_일시중지_갱신은_기다렸다가_반영된다() throws Exception {
        long campaignId = plan(30, false);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> rebuild = pool.submit(() -> tx.executeWithoutResult(status -> {
                planRepository.findForShare(campaignId, DAY).orElseThrow();
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            LocalDateTime changedAt = LocalDateTime.now();
            Future<Integer> pause = pool.submit(
                    () -> tx.execute(status -> planRepository.applyStatus(campaignId, DAY, true, changedAt, changedAt)));
            assertThatThrownBy(() -> pause.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            rebuild.get(30, TimeUnit.SECONDS);
            assertThat(pause.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select paused from budget_daily_plan where campaign_id = ?", Boolean.class, campaignId)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
    @TestConfiguration
    static class TimeConfig {

        @Bean
        @Primary
        Clock deductionTestClock() {
            return Clock.fixed(Instant.parse("2026-10-14T02:30:00Z"), ClockConfig.ZONE);
        }
    }
}
