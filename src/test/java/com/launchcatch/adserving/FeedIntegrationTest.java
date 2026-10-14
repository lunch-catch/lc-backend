package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 권한 축이다. 피드 요청이 MEMBER 에게만 열려 있고, 통과한 요청이 서빙 로그까지 남기는지 본다
@SpringBootTest
@AutoConfigureMockMvc
class FeedIntegrationTest extends IntegrationTestSupport {

    private static final Long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    // 2026-10-15 10:15:30 KST, 서빙 시간대 안이다
    private static final Instant NOW = Instant.parse("2026-10-15T01:15:30Z");
    private static final String LATITUDE = "37.4979";
    private static final String LONGITUDE = "127.0276";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @MockitoBean Clock clock;
    @MockitoBean MemberQueryService memberQueryService;
    @MockitoBean BudgetQueryService budgetQueryService;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.withZone(ClockConfig.ZONE)).thenReturn(Clock.fixed(NOW, ClockConfig.ZONE));
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(new MemberInfo(MEMBER_ID, "닉네임",
                MemberStatus.ACTIVE, true, true, true, null, null, Gender.FEMALE, AgeGroup.AGE_20S)));
        when(budgetQueryService.getProgress(anyCollection(), any(), any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> new BudgetProgress(id, 100_000, 10, 0, 0, 0.5)).toList();
        });
        cleanUp();
        // 요청 위치에서 북쪽으로 약 420m 다
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code, latitude,
                    longitude, target_radius, target_gender, target_age_groups, discount_target_type, discount_type,
                    discount_value, usable_start_time, usable_end_time, issue_quantity, campaign_created_at,
                    campaign_state, created_at, updated_at)
                VALUES (?, 101, 55, '점심식당', 'KOREAN', 37.5016771, 127.0276, 1000, 'ALL', 'AGE_20S,AGE_30S',
                    'ALL', 'PERCENT', 20, '12:00', '14:00', 30, '2026-09-01 09:00:00', 'SERVABLE', NOW(6), NOW(6))
                """, TODAY);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void MEMBER는_피드를_받고_서빙_로그와_접속_위치가_남는다() throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", LATITUDE).param("longitude", LONGITUDE)
                        .with(user(new CustomUserDetails(MEMBER_ID, Role.MEMBER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.status").value("SERVING"))
                .andExpect(jsonPath("$.data.radiusM").value(5000))
                .andExpect(jsonPath("$.data.nextFeedAvailableAt").value("2026-10-15T10:15:30+09:00"))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101))
                .andExpect(jsonPath("$.data.cards[0].distanceM").value(420))
                .andExpect(jsonPath("$.data.cards[0].usableStartTime").value("12:00"))
                .andExpect(jsonPath("$.data.cards[0].posterUrl").value("/v1/campaigns/101/poster"));

        // 서빙 로그와 접속 위치는 응답 뒤에 다른 스레드가 쓴다
        assertThat(waitForRows("SELECT COUNT(*) FROM serve_log WHERE member_id = ?")).isEqualTo(1);
        assertThat(waitForRows("SELECT COUNT(*) FROM feed_visit_daily WHERE member_id = ?")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lat_rounded FROM feed_visit_daily WHERE member_id = ?", BigDecimal.class, MEMBER_ID))
                .isEqualByComparingTo("37.498");
        assertThat(redis.keys("serve:*")).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "ADMIN", "SUPER_ADMIN"})
    void MEMBER가_아니면_403이다(Role role) throws Exception {
        mockMvc.perform(get("/v1/feed").param("latitude", LATITUDE).param("longitude", LONGITUDE)
                        .with(user(new CustomUserDetails(MEMBER_ID, role))))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM serve_log", Integer.class)).isZero();
    }

    private int waitForRows(String countSql) throws InterruptedException {
        int count = 0;
        for (int attempt = 0; attempt < 50 && count == 0; attempt++) {
            count = jdbcTemplate.queryForObject(countSql, Integer.class, MEMBER_ID);
            if (count == 0) {
                Thread.sleep(100);
            }
        }
        return count;
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM ad_candidate");
        jdbcTemplate.update("DELETE FROM serve_log");
        jdbcTemplate.update("DELETE FROM feed_visit_daily");
        Set<String> keys = redis.keys("*");
        keys.stream()
                .filter(key -> key.startsWith("serve:") || key.startsWith("feed"))
                .forEach(redis::delete);
    }
}
