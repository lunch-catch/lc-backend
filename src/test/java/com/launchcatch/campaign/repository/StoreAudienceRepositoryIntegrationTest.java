package com.launchcatch.campaign.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.entity.StoreAudience;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
class StoreAudienceRepositoryIntegrationTest {

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
    private StoreAudienceRepository storeAudienceRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void 같은_유일_키로_두_번_부르면_새_행이_아니라_덮어쓴다() {
        LocalDateTime firstNow = LocalDateTime.of(2026, 10, 12, 0, 0);
        storeAudienceRepository.upsert(1L, 1000, "MALE", "AGE_20S", 10, LocalDate.of(2026, 10, 12), firstNow);
        entityManager.flush();
        entityManager.clear();

        LocalDateTime secondNow = LocalDateTime.of(2026, 10, 13, 0, 0);
        storeAudienceRepository.upsert(1L, 1000, "MALE", "AGE_20S", 25, LocalDate.of(2026, 10, 13), secondNow);
        entityManager.flush();
        entityManager.clear();

        assertThat(storeAudienceRepository.findAll()).hasSize(1);
        StoreAudience row = storeAudienceRepository.findAll().get(0);
        assertThat(row.getMemberCount()).isEqualTo(25);
        assertThat(row.getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(row.getCreatedAt()).isEqualTo(firstNow);
        assertThat(row.getUpdatedAt()).isEqualTo(secondNow);
    }

    @Test
    void 다른_유일_키는_다른_행으로_쌓인다() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 13, 0, 0);
        storeAudienceRepository.upsert(1L, 1000, "MALE", "AGE_20S", 10, LocalDate.of(2026, 10, 13), now);
        storeAudienceRepository.upsert(1L, 1000, "FEMALE", "AGE_20S", 5, LocalDate.of(2026, 10, 13), now);
        storeAudienceRepository.upsert(2L, 1000, "MALE", "AGE_20S", 7, LocalDate.of(2026, 10, 13), now);
        entityManager.flush();
        entityManager.clear();

        assertThat(storeAudienceRepository.findAll()).hasSize(3);
    }
}
