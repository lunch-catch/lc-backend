package com.launchcatch.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 중단 표시의 조건부 갱신을 실제 MySQL 8.4 에서 검증한다.
 * 엔티티 매핑이 V1, V2 스키마와 맞는지는 컨텍스트 기동(ddl-auto validate)이 함께 확인한다.
 */
@Testcontainers
@SpringBootTest
@Transactional
class BudgetDailyPlanRepositoryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    private static final Long CAMPAIGN = 12L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 10, 0, 0, 100_000);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 1, 10, 0, 0, 200_000);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 10, 0, 1);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired
    private BudgetDailyPlanRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        plan(CAMPAIGN, DAY);
        plan(CAMPAIGN, DAY.plusDays(1));
        plan(13L, DAY);
    }

    private void plan(Long campaignId, LocalDate businessDate) {
        jdbc.update("""
                insert into budget_daily_plan (campaign_id, owner_id, business_date, daily_budget, unit_price,
                                               created_at, updated_at)
                values (?, 7, ?, 10000, 10, '2026-10-01 00:00:05', '2026-10-01 00:00:05')
                """, campaignId, businessDate);
    }

    private Map<String, Object> row(Long campaignId, LocalDate businessDate) {
        return jdbc.queryForMap(
                "select paused, status_changed_at from budget_daily_plan where campaign_id = ? and business_date = ?",
                campaignId, businessDate);
    }

    private static boolean paused(Map<String, Object> row) {
        return Boolean.TRUE.equals(row.get("paused")) || Integer.valueOf(1).equals(row.get("paused"));
    }

    @Test
    void 처음_받은_변경은_반영하고_다른_날과_다른_캠페인은_건드리지_않는다() {
        int updated = repository.applyStatus(CAMPAIGN, DAY, true, T1, NOW);

        assertThat(updated).isOne();
        assertThat(paused(row(CAMPAIGN, DAY))).isTrue();
        assertThat(row(CAMPAIGN, DAY).get("status_changed_at")).isEqualTo(T1);
        assertThat(paused(row(CAMPAIGN, DAY.plusDays(1)))).isFalse();
        assertThat(paused(row(13L, DAY))).isFalse();
    }

    @Test
    void 늦은_변경이_먼저_오면_나중에_온_이른_변경은_무시한다() {
        assertThat(repository.applyStatus(CAMPAIGN, DAY, false, T2, NOW)).isOne();
        assertThat(repository.applyStatus(CAMPAIGN, DAY, true, T1, NOW)).isZero();

        assertThat(paused(row(CAMPAIGN, DAY))).isFalse();
        assertThat(row(CAMPAIGN, DAY).get("status_changed_at")).isEqualTo(T2);
    }

    @Test
    void 같은_변경이_두_번_오면_두_번째는_반영하지_않는다() {
        assertThat(repository.applyStatus(CAMPAIGN, DAY, true, T1, NOW)).isOne();
        assertThat(repository.applyStatus(CAMPAIGN, DAY, true, T1, NOW)).isZero();
    }

    @Test
    void 이른_변경_뒤에_온_늦은_변경은_반영한다() {
        repository.applyStatus(CAMPAIGN, DAY, true, T1, NOW);

        assertThat(repository.applyStatus(CAMPAIGN, DAY, false, T2, NOW)).isOne();
        assertThat(paused(row(CAMPAIGN, DAY))).isFalse();
    }

    @Test
    void 그날_계획_행이_없으면_아무것도_바꾸지_않는다() {
        assertThat(repository.applyStatus(99L, DAY, true, T1, NOW)).isZero();
    }
}
