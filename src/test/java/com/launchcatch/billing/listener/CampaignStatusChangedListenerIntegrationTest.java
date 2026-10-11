package com.launchcatch.billing.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 상태 변경 수신을 실제 트랜잭션, MySQL, Valkey 로 끝까지 확인한다.
 * 중단 표시는 발행자와 함께 커밋되고, 해시는 커밋된 뒤에만 바뀌며, 롤백되면 둘 다 그대로인지가 핵심이다.
 * 커밋이 있어야 하므로 테스트 트랜잭션을 쓰지 않고, 테스트마다 다른 캠페인 ID 로 서로를 가른다.
 */
@Testcontainers
@SpringBootTest(properties = "jwt.secret=test-only-secret-not-used-anywhere-else-0123456789abcdef")
class CampaignStatusChangedListenerIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>(DockerImageName.parse("valkey/valkey:9"))
            .withExposedPorts(6379);

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 1, 11, 0);

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    private void plan(Long campaignId) {
        jdbc.update("""
                insert into budget_daily_plan (campaign_id, owner_id, business_date, daily_budget, unit_price,
                                               created_at, updated_at)
                values (?, 7, ?, 10000, 10, '2026-10-01 00:00:05', '2026-10-01 00:00:05')
                """, campaignId, DAY);
    }

    // 00:00 예약이 만들 해시를 흉내 낸다
    private String hash(Long campaignId) {
        String key = "billing:day:" + DAY + ":" + campaignId;
        redis.opsForHash().putAll(key, Map.of("reserved", "10000", "spend", "0", "paused", "0"));
        return key;
    }

    private boolean pausedInDb(Long campaignId) {
        Object paused = jdbc.queryForObject(
                "select paused from budget_daily_plan where campaign_id = ? and business_date = ?",
                Object.class, campaignId, DAY);
        return Boolean.TRUE.equals(paused) || Integer.valueOf(1).equals(paused);
    }

    private void publishInTransaction(CampaignStatusChangedEvent event) {
        transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(event));
    }

    @Test
    void 커밋되면_DB와_해시가_모두_중단으로_바뀐다() {
        plan(201L);
        String key = hash(201L);

        publishInTransaction(new CampaignStatusChangedEvent(201L, DAY, false, T1));

        assertThat(pausedInDb(201L)).isTrue();
        assertThat(redis.opsForHash().get(key, "paused")).isEqualTo("1");
        assertThat(redis.opsForHash().get(key, "reserved")).isEqualTo("10000");
    }

    @Test
    void 발행자가_롤백하면_DB와_해시_모두_그대로다() {
        plan(202L);
        String key = hash(202L);

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishEvent(new CampaignStatusChangedEvent(202L, DAY, false, T1));
            status.setRollbackOnly();
        });

        assertThat(pausedInDb(202L)).isFalse();
        assertThat(redis.opsForHash().get(key, "paused")).isEqualTo("0");
        assertThat(redis.opsForHash().hasKey(key, "changedAt")).isFalse();
    }

    @Test
    void 늦은_변경이_먼저_커밋되면_나중에_온_이른_변경은_DB와_해시_모두에서_무시된다() {
        plan(203L);
        String key = hash(203L);

        publishInTransaction(new CampaignStatusChangedEvent(203L, DAY, true, T2));
        publishInTransaction(new CampaignStatusChangedEvent(203L, DAY, false, T1));

        assertThat(pausedInDb(203L)).isFalse();
        assertThat(redis.opsForHash().get(key, "paused")).isEqualTo("0");
    }

    @Test
    void 그날_계획_행이_없으면_해시도_건드리지_않는다() {
        String key = hash(204L);

        publishInTransaction(new CampaignStatusChangedEvent(204L, DAY, false, T1));

        assertThat(redis.opsForHash().get(key, "paused")).isEqualTo("0");
        assertThat(jdbc.queryForObject(
                "select count(*) from budget_daily_plan where campaign_id = 204", Long.class)).isZero();
    }

    @Test
    void 해시가_없으면_DB만_바꾸고_해시를_만들지_않는다() {
        plan(205L);

        publishInTransaction(new CampaignStatusChangedEvent(205L, DAY, false, T1));

        assertThat(pausedInDb(205L)).isTrue();
        assertThat(redis.hasKey("billing:day:" + DAY + ":205")).isFalse();
    }

    @Test
    void 발행자에게_트랜잭션이_없어도_수신이_자기_트랜잭션으로_커밋하고_해시를_맞춘다() {
        plan(206L);
        String key = hash(206L);

        publisher.publishEvent(new CampaignStatusChangedEvent(206L, DAY, false, T1));

        assertThat(pausedInDb(206L)).isTrue();
        assertThat(redis.opsForHash().get(key, "paused")).isEqualTo("1");
    }
}
