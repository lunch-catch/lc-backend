package com.launchcatch.member.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.member.entity.Member;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
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
class MemberRepositoryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Refresh Token 백업은 현재 해시가 일치할 때만 지운다")
    void Refresh_Token_백업은_현재_해시가_일치할_때만_지운다() {
        Member member = memberRepository.saveAndFlush(Member.create("kakao-123", "점심헌터", null));
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 13, 0);
        LocalDateTime expiresAt = now.plusDays(14);

        int saved = memberRepository.updateRefreshTokenBackup(member.getId(), "hash-1", expiresAt, now);
        entityManager.clear();

        assertThat(saved).isOne();
        assertThat(memberRepository.findByRefreshTokenHash("hash-1"))
                .hasValueSatisfying(found -> {
                    assertThat(found.getId()).isEqualTo(member.getId());
                    assertThat(found.getRefreshTokenExpiresAt()).isEqualTo(expiresAt);
                });

        int mismatched = memberRepository.clearRefreshTokenBackupIfHashMatches(member.getId(), "hash-2", now.plusMinutes(1));
        int cleared = memberRepository.clearRefreshTokenBackupIfHashMatches(member.getId(), "hash-1", now.plusMinutes(1));
        entityManager.clear();

        assertThat(mismatched).isZero();
        assertThat(cleared).isOne();
        assertThat(memberRepository.findByRefreshTokenHash("hash-1")).isEmpty();
    }
}
