package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import java.math.BigDecimal;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 권한 축이다. 피드 요청이 MEMBER 에게만 열려 있고, 통과한 요청이 DB 까지 남기는지 본다.
 *
 * 빈을 목으로 바꾸지 않는다. 목 조합이 다르면 컨텍스트가 하나 더 떠서 테스트 JVM 의 힙을 넘긴다.
 * 그래서 시각도 실제 시각이다. 후보를 넣지 않아 시간대 밖이면 OUT_OF_SERVING_HOURS, 안이면 NO_CANDIDATE 가 오고
 * 둘 다 그날 첫 요청 위치를 남긴다. 카드를 채우는 경로는 FeedServiceTest 가 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FeedIntegrationTest extends IntegrationTestSupport {

    // 다른 테스트의 회원과 겹치지 않게 큰 값을 쓴다
    private static final Long MEMBER_ID = 236_001L;
    private static final String LATITUDE = "37.4979";
    private static final String LONGITUDE = "127.0276";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("""
                INSERT INTO member (member_id, provider_user_id, nickname, status, updated_at)
                VALUES (?, 'feed-integration', '피드회원', 'ACTIVE', NOW(6))
                """, MEMBER_ID);
        jdbcTemplate.update("""
                INSERT INTO member_profile (member_id, gender, age_group, onboarding_completed_at, created_at, updated_at)
                VALUES (?, 'FEMALE', 'AGE_20S', NOW(6), NOW(6), NOW(6))
                """, MEMBER_ID);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void MEMBER는_피드를_받고_그날_접속_위치가_남는다() throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", LATITUDE).param("longitude", LONGITUDE)
                        .with(user(new CustomUserDetails(MEMBER_ID, Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value(Matchers.oneOf("OUT_OF_SERVING_HOURS", "NO_CANDIDATE")))
                .andExpect(jsonPath("$.data.cards").isEmpty());

        // 접속 위치는 응답 뒤에 다른 스레드가 쓴다
        assertThat(waitForVisit()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lat_rounded FROM feed_visit_daily WHERE member_id = ?", BigDecimal.class, MEMBER_ID))
                .isEqualByComparingTo("37.498");
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "ADMIN", "SUPER_ADMIN"})
    void MEMBER가_아니면_403이다(Role role) throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", LATITUDE).param("longitude", LONGITUDE)
                        .with(user(new CustomUserDetails(MEMBER_ID, role))))
                .andExpect(status().isForbidden());

        assertThat(redis.hasKey("feedRateLimit:" + MEMBER_ID)).isFalse();
    }

    private int waitForVisit() throws InterruptedException {
        int count = 0;
        for (int attempt = 0; attempt < 50 && count == 0; attempt++) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM feed_visit_daily WHERE member_id = ?", Integer.class, MEMBER_ID);
            if (count == 0) {
                Thread.sleep(100);
            }
        }
        return count;
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ad_candidate");
        jdbcTemplate.update("DELETE FROM feed_visit_daily WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM serve_log WHERE member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", MEMBER_ID);
        redis.delete("feedRateLimit:" + MEMBER_ID);
        redis.delete("feedSession:" + MEMBER_ID);
    }
}
