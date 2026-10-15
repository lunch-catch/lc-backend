package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
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
class ImpressionDeductionIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Long OWNER = 7L;
    private static final long UNIT = 10L;
    private static final AtomicLong CAMPAIGN_SEQ = new AtomicLong(1000);

    @Autowired PointDeductor deductor;
    @Autowired BudgetQueryService budgetQuery;
    @Autowired BudgetDailyPlanRepository planRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry meterRegistry;

    @AfterEach
    void clean() {
        jdbc.update("delete from point_ledger");
        jdbc.update("delete from deduction_result");
        jdbc.update("delete from budget_daily_plan");
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    private long plan(long unitsOfBudget, boolean paused) {
        long campaignId = CAMPAIGN_SEQ.incrementAndGet();
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
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(serveIds.size(), 16));
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
    void 같은_캠페인에_동시_50건이_와도_예약액을_넘기지_않고_소진이_원장과_맞는다() throws Exception {
        long campaignId = plan(30, false);

        List<DeductionStatus> results = deductConcurrently(serveIds(50), campaignId);

        assertThat(count(DeductionStatus.BILLED, results)).isEqualTo(30);
        assertThat(count(DeductionStatus.NOT_BILLED_BUDGET, results)).isEqualTo(20);
        assertThat(rows("deduction_result", campaignId)).isEqualTo(50);
        assertThat(rows("point_ledger", campaignId)).isEqualTo(30);
        assertThat(ledgerDeductSum(campaignId)).isEqualTo(-30 * UNIT);
        assertThat(spendInValkey(campaignId)).isEqualTo(30 * UNIT);
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
                List.of(first, second, third), DAY, LocalDateTime.of(2026, 10, 14, 11, 30));

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
    void 트랜잭션_밖에서_부르면_예외다() {
        long campaignId = plan(30, false);

        assertThatThrownBy(() -> deductor.deduct(UUID.randomUUID(), campaignId, DAY))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 해시가_사라진_뒤_동시에_불러도_재구성은_한_번이고_소진이_이어진다() throws Exception {
        long campaignId = plan(30, false);
        for (int i = 0; i < 5; i++) {
            deductInTx(UUID.randomUUID(), campaignId);
        }
        redis.delete("billing:day:" + DAY + ":" + campaignId);
        double rebuildBefore = meterRegistry.counter("billing_spend_hash_rebuild_total").count();

        List<DeductionStatus> results = deductConcurrently(serveIds(40), campaignId);

        double rebuilds = meterRegistry.counter("billing_spend_hash_rebuild_total").count() - rebuildBefore;
        assertThat(rebuilds).isEqualTo(1.0);
        assertThat(count(DeductionStatus.BILLED, results)).isEqualTo(25);
        assertThat(ledgerDeductSum(campaignId)).isEqualTo(-30 * UNIT);
        assertThat(spendInValkey(campaignId)).isEqualTo(30 * UNIT);
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
}
