package com.launchcatch.campaign.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@Transactional
class CampaignRepositoryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    private static final String DUMMY_JWT_SECRET = "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 예산과_기간이_없는_단계별_저장_중인_DRAFT도_다시_읽힌다() {
        Campaign campaign = Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.of(2026, 10, 13, 9, 0));
        Long id = campaignRepository.save(campaign).getId();
        entityManager.flush();
        entityManager.clear();

        Campaign reloaded = campaignRepository.findById(id).orElseThrow();

        assertThat(reloaded.getDailyBudget()).isNull();
        assertThat(reloaded.getStartDate()).isNull();
        assertThat(reloaded.getEndDate()).isNull();
    }

    @Test
    void PAUSED_캠페인이_종료되면_paused_reason이_지워져_CHECK_제약을_어기지_않는다() {
        Campaign campaign = Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.of(2026, 10, 13, 9, 0));
        campaign.schedule(LocalDateTime.of(2026, 10, 13, 9, 0));
        campaign.activate(LocalDateTime.of(2026, 10, 13, 9, 0));
        campaign.pause(PauseReason.ADMIN, LocalDateTime.of(2026, 10, 13, 9, 0));
        Long id = campaignRepository.save(campaign).getId();
        entityManager.flush();
        entityManager.clear();

        Campaign reloaded = campaignRepository.findById(id).orElseThrow();
        reloaded.end(LocalDateTime.of(2026, 10, 13, 10, 0));
        entityManager.flush();

        assertThat(reloaded.getPausedReason()).isNull();
    }

    @Test
    void findByStoreIdIn의_조회가_store_id_인덱스를_탄다() {
        String plan = explainJson("SELECT * FROM campaign WHERE store_id IN (1)");

        assertThat(plan).contains("idx_campaign_store_id");
    }

    @Test
    void findServableOn의_조회가_status_기간_인덱스를_탄다() {
        String plan = explainJson(
                "SELECT * FROM campaign WHERE start_date <= '2026-10-13' AND end_date >= '2026-10-13' "
                        + "AND (status = 'ACTIVE' "
                        + "OR (status = 'PAUSED' AND paused_reason IN ('OWNER', 'NO_POINTS')))");

        assertThat(plan).contains("idx_campaign_status_period");
    }

    private String explainJson(String sql) {
        return (String) entityManager.createNativeQuery("EXPLAIN FORMAT=JSON " + sql).getSingleResult();
    }
}
