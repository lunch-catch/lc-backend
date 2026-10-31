package com.lunchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * V12 가 released_at 을 채우는 UPDATE 를 이미 적용된 스키마 위에서 다시 돌려 본다(#1049).
 * 해제가 끝났다는 증거인 RELEASE 원장이 있는 계획만, 그 원장의 시각으로 채워야 한다.
 */
class PlanReleasedAtMigrationIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 26);
    private static final long OWNER = 960_001L;
    private static final LocalDateTime RELEASED = LocalDateTime.of(2026, 10, 27, 0, 0, 3, 123_000);

    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("delete from point_ledger where owner_id = ?", OWNER);
        jdbc.update("delete from budget_daily_plan where owner_id = ?", OWNER);
    }

    private void plan(long campaignId) {
        jdbc.update("""
                insert into budget_daily_plan
                    (campaign_id, owner_id, business_date, daily_budget, unit_price, paused, created_at, updated_at)
                values (?, ?, ?, 1000, 10, false, now(6), now(6))
                """, campaignId, OWNER, DAY);
    }

    private LocalDateTime releasedAt(long campaignId) {
        return jdbc.queryForObject("select released_at from budget_daily_plan where campaign_id = ? and business_date = ?",
                LocalDateTime.class, campaignId, DAY);
    }

    private String backfill() throws Exception {
        String sql = new ClassPathResource("db/migration/billing/V12__billing_plan_released_at.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        return Arrays.stream(sql.split(";"))
                .map(statement -> statement.lines().filter(line -> !line.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim())
                .filter(statement -> statement.startsWith("UPDATE"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void RELEASE_원장이_있는_계획만_그_원장의_시각으로_채운다() throws Exception {
        long released = 960_101L;
        long notYetReleased = 960_102L;
        plan(released);
        plan(notYetReleased);
        jdbc.update("""
                insert into point_ledger (owner_id, campaign_id, business_date, entry_type, source, amount, created_at, updated_at)
                values (?, ?, ?, 'RELEASE', 'RECONCILIATION', 300, ?, ?)
                """, OWNER, released, DAY, RELEASED, RELEASED);

        jdbc.update(backfill());

        assertThat(releasedAt(released)).isEqualTo(RELEASED);
        // 해제 전이거나 해제가 실패한 계획이다. 닫으면 릴레이가 그 뒤 넘기는 노출이 과금되지 않는다
        assertThat(releasedAt(notYetReleased)).isNull();
    }
}
