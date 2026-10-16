package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.config.BillingScheduleProperties;
import com.launchcatch.billing.dto.ServeIds;
import com.launchcatch.billing.repository.BudgetDailyPlanRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.service.DailyReleaseService;
import com.launchcatch.billing.service.DailyReservationService;
import com.launchcatch.billing.service.SpendHashRebuildService;
import com.launchcatch.billing.service.SpendMismatchRecorder;
import com.launchcatch.billing.service.SpendReconciliationService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.ops.contract.BatchExecutionQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 13:30 해제·대사(구현 순서 11번)의 입구를 실제 MySQL 8.4 와 Valkey 9 까지 함께 띄워 검증한다.
 *
 * 입구는 DailyReleaseService.reconcileAndRelease 다. 작업 점유와 claim/succeed/fail 은
 * DailyReleaseScheduler 의 단위 테스트(목)와 BatchExecutionIntegrationTest 가 이미 본다. 여기서는
 * 00:00 예약이 만든 당일 계획이 있다고 가정하고 대조와 해제만 확인한다(DailyReservationIntegrationTest
 * 가 run 을 직접 부르는 것과 같은 자리다).
 *
 * reconcileToday 는 실제 시계로 서빙 시간대를 거부한다. 이 테스트가 실제로 도는 시각과 무관하게 돌아야
 * 해서, 컨텍스트의 Clock 빈을 바꾸지 않고(다른 billing 통합 테스트와 컨텍스트를 공유하기 위해서다.
 * AbstractBillingIntegrationTest 참고) 그 Clock 만 13:30 이후로 고정한 서비스 인스턴스를 여기서 직접
 * 만든다. 나머지 협력자는 전부 실제 빈이라 DB 와 Valkey 는 그대로 실제로 호출한다.
 *
 * 다른 테스트의 캠페인과 섞이지 않게 먼 미래 영업일과 큰 캠페인, 점주 ID 를 쓴다.
 */
class DailyReleaseIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2030, 3, 10);
    private static final long OWNER = 950_000L;
    private static final long CAMPAIGN = 950_001L;
    private static final String JOB = "TEST_BILLING_DAILY_RELEASE";

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired BudgetDailyPlanRepository planRepository;
    @Autowired PointLedgerRepository ledgerRepository;
    @Autowired SpendMismatchRecorder mismatchRecorder;
    @Autowired DailySpendCounter spendCounter;
    @Autowired SpendHashRebuildService hashRebuild;
    @Autowired BatchExecutionQueryService batchExecutions;
    @Autowired BillingScheduleProperties schedule;
    @Autowired DailyReservationService reservation;

    // 서빙 시간대(10:00~12:59)와 노출 인정 시간(13:00~13:30)이 끝난 뒤로 고정한다
    private DailyReleaseService releaseServiceAfterReleaseTime() {
        Clock fixed = Clock.fixed(LocalDate.now(ClockConfig.ZONE).atTime(LocalTime.of(14, 0))
                .atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        SpendReconciliationService reconciliation = new SpendReconciliationService(planRepository, ledgerRepository,
                mismatchRecorder, spendCounter, hashRebuild, batchExecutions, schedule, fixed);
        return new DailyReleaseService(reconciliation, reservation);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from settlement_mismatch where target_id = ?", CAMPAIGN);
        jdbc.update("delete from point_ledger where campaign_id = ?", CAMPAIGN);
        jdbc.update("delete from deduction_result where campaign_id = ?", CAMPAIGN);
        jdbc.update("delete from budget_daily_plan where campaign_id = ?", CAMPAIGN);
        redis.delete("billing:day:" + DAY + ":" + CAMPAIGN);
    }

    private void plan(long dailyBudget) {
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, ?, 1000, false, now(6), now(6))
                """, CAMPAIGN, OWNER, DAY, dailyBudget);
    }

    // 원장 소진 합은 deduction_result 와 serve_id 로 묶여 그 영업일인지를 본다(PointLedgerRepository.sumDeductForCampaignsOn)
    private void deduct(long unitPrice, int count) {
        for (int i = 0; i < count; i++) {
            byte[] serveId = ServeIds.toBytes(UUID.randomUUID());
            jdbc.update("""
                    insert into deduction_result
                        (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                    values (?, ?, ?, ?, 'BILLED', ?, now(6), now(6))
                    """, serveId, CAMPAIGN, OWNER, DAY, unitPrice);
            jdbc.update("""
                    insert into point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
                    values (?, ?, ?, 'DEDUCT', 'REALTIME', ?, now(6), now(6))
                    """, OWNER, CAMPAIGN, serveId, -unitPrice);
        }
    }

    // 카운터가 원장과 어긋난 해시를 바로 심는다. evict 뒤 rebuildIfAbsent 가 이 값을 원장 기준으로 되돌려야 한다
    private void seedMismatchedCounter(long reserved, long wrongSpend) {
        redis.opsForHash().putAll("billing:day:" + DAY + ":" + CAMPAIGN, Map.of(
                "reserved", String.valueOf(reserved),
                "spend", String.valueOf(wrongSpend),
                "paused", "0",
                "changedAt", "0"));
    }

    private String counterSpend() {
        return (String) redis.opsForHash().get("billing:day:" + DAY + ":" + CAMPAIGN, "spend");
    }

    private List<Map<String, Object>> mismatchRows() {
        return jdbc.queryForList(
                "select resolution_type, expected_value, actual_value from settlement_mismatch "
                        + "where check_type = 'CAMPAIGN_SPEND_VS_LEDGER' and target_id = ? and business_date = ?",
                CAMPAIGN, DAY);
    }

    private List<Long> releases() {
        return jdbc.queryForList("""
                select amount from point_ledger
                 where entry_type = 'RELEASE' and campaign_id = ? and business_date = ?
                """, Long.class, CAMPAIGN, DAY);
    }

    @Test
    void 카운터가_원장과_다르면_원장_기준으로_다시_만들고_남은_예약을_해제하며_다시_돌려도_중복되지_않는다() {
        plan(5000L);
        deduct(1000L, 3);
        seedMismatchedCounter(5000L, 9999L);
        DailyReleaseService releaseService = releaseServiceAfterReleaseTime();

        releaseService.reconcileAndRelease(DAY, JOB);

        assertThat(counterSpend()).isEqualTo("3000");
        assertThat(mismatchRows()).singleElement().satisfies(row -> {
            assertThat(row.get("resolution_type")).isEqualTo("CACHE_REBUILD");
            assertThat(row.get("expected_value")).isEqualTo(3000L);
            assertThat(row.get("actual_value")).isEqualTo(9999L);
        });
        assertThat(releases()).containsExactly(2000L);

        releaseService.reconcileAndRelease(DAY, JOB);

        assertThat(mismatchRows()).hasSize(1);
        assertThat(releases()).containsExactly(2000L);
    }
}
