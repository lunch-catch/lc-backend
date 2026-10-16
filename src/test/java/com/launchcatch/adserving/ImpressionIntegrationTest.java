package com.launchcatch.adserving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/*
 * POST /v1/impressions 를 HTTP 입구부터 MySQL 의 impression_log 와 Valkey 의 카운터까지 본다.
 * 돈이 걸린 경로라 통합 테스트로 둔다. 노출 저장과 정산의 차감이 한 트랜잭션인지, 차감이 실패하면 노출도 되돌아가는지 본다.
 * 정산의 판정 자체는 정산의 테스트가 본다. 여기서는 contract 를 목으로 두고 트랜잭션 안에서 불렸는지만 본다.
 */
@DisplayName("노출 수집 통합")
class ImpressionIntegrationTest extends AbstractAdservingIntegrationTest {

    private static final long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant SERVED_AT = LocalDateTime.of(2026, 10, 1, 10, 15, 30).atZone(ClockConfig.ZONE).toInstant();
    private static final Instant NOW = SERVED_AT.plusSeconds(2);
    private static final String COUNTER_KEY = "adserving:validImpressions:" + TODAY;

    @Autowired ServeRecordRepository serveRecordRepository;

    private final UUID serveId = UUID.randomUUID();
    private final AtomicBoolean deductedInTransaction = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        doAnswer(invocation -> {
            deductedInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return DeductionStatus.BILLED;
        }).when(pointDeductor).deduct(any(), any(), any());
        serveRecordRepository.saveAll(List.of(new ServeRecord(serveId, MEMBER_ID, 101L, 55L, SlotType.ALLOCATION, 1, SERVED_AT)));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM impression_log");
        jdbcTemplate.update("DELETE FROM ad_candidate");
        jdbcTemplate.update("DELETE FROM serve_log");
        jdbcTemplate.update("DELETE FROM feed_visit_daily");
        redisTemplate.delete(List.of(COUNTER_KEY, "adserving:serve:" + serveId, "adserving:feedRequests:" + MEMBER_ID,
                "adserving:servedDaily:" + TODAY + ":" + MEMBER_ID, "adserving:servedSession:" + MEMBER_ID));
    }

    private String body(UUID id, long campaignId) {
        return """
                {"serveId": "%s", "campaignId": %d, "displayedAt": "2026-10-01T10:15:31.880+09:00",
                 "latitude": 37.4979, "longitude": 127.0276, "viewedMs": 2140}
                """.formatted(id, campaignId);
    }

    private org.springframework.test.web.servlet.ResultActions send(String json, Role role) throws Exception {
        return mockMvc.perform(post("/v1/impressions").contentType(MediaType.APPLICATION_JSON).content(json)
                .with(user(new CustomUserDetails(MEMBER_ID, role))));
    }

    private long rows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM impression_log", Long.class);
    }

    private Object counter() {
        return redisTemplate.opsForHash().get(COUNTER_KEY, "101");
    }

    @Test
    void 유효_노출은_202_로_받고_한_트랜잭션_안에서_차감을_부르며_커밋_뒤_카운터를_올린다() throws Exception {
        send(body(serveId, 101L), Role.MEMBER)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.validity").value("VALID"))
                .andExpect(jsonPath("$.data.invalidReason").isEmpty());

        assertThat(jdbcTemplate.queryForMap("""
                SELECT BIN_TO_UUID(serve_id) AS serve_id, member_id, business_date, campaign_id, store_id, slot_type,
                       slot_position, validity, invalid_reason, sold_out, lat_rounded, lng_rounded, viewed_ms
                FROM impression_log
                """)).containsEntry("serve_id", serveId.toString()).containsEntry("member_id", MEMBER_ID)
                .containsEntry("campaign_id", 101L).containsEntry("store_id", 55L)
                .containsEntry("slot_type", "ALLOCATION").containsEntry("validity", "VALID")
                .containsEntry("invalid_reason", null).containsEntry("lat_rounded", new BigDecimal("37.498"))
                .containsEntry("viewed_ms", 2140);
        verify(pointDeductor).deduct(serveId, 101L, TODAY);
        assertThat(deductedInTransaction).isTrue();
        assertThat(counter()).isEqualTo("1");
    }

    @Test
    void 같은_사용자가_같은_카드를_다시_보내면_처음_판정을_돌려주고_한_번만_차감하고_센다() throws Exception {
        send(body(serveId, 101L), Role.MEMBER).andExpect(status().isAccepted());

        send(body(serveId, 101L), Role.MEMBER)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.validity").value("VALID"));

        assertThat(rows()).isEqualTo(1);
        verify(pointDeductor, times(1)).deduct(any(), any(), any());
        assertThat(counter()).isEqualTo("1");
    }

    @Test
    void 기록이_없는_카드는_202_와_EXPIRED_OR_UNKNOWN_이고_차감도_카운터도_없다() throws Exception {
        send(body(UUID.randomUUID(), 101L), Role.MEMBER)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.validity").value("INVALID"))
                .andExpect(jsonPath("$.data.invalidReason").value("EXPIRED_OR_UNKNOWN"));

        assertThat(rows()).isEqualTo(1);
        verify(pointDeductor, never()).deduct(any(), any(), any());
        assertThat(counter()).isNull();
    }

    @Test
    void 차감이_실패하면_노출도_저장되지_않고_카운터도_오르지_않는다() throws Exception {
        doThrow(new IllegalStateException("그날 예산 계획이 없다")).when(pointDeductor).deduct(any(), any(), any());

        send(body(serveId, 101L), Role.MEMBER).andExpect(status().isInternalServerError());

        assertThat(rows()).isZero();
        assertThat(counter()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
    void MEMBER_가_아니면_403_이고_저장하지_않는다(Role role) throws Exception {
        send(body(serveId, 101L), role).andExpect(status().isForbidden());

        assertThat(rows()).isZero();
    }

    @Test
    void serveId_형식이_틀리거나_필수_필드가_빠지면_COMMON_002_다() throws Exception {
        send(body(serveId, 101L).replace(serveId.toString(), "not-a-uuid"), Role.MEMBER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
        send("{\"serveId\": \"" + serveId + "\", \"campaignId\": 101}", Role.MEMBER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void 노출이_쌓여_노출_상한에_닿은_캠페인은_피드에서_빠진다() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO ad_candidate (business_date, campaign_id, store_id, store_name, category_code,
                    latitude, longitude, target_radius, target_gender, target_age_groups,
                    discount_target_type, discount_type, discount_value, usable_start_time, usable_end_time,
                    issue_quantity, campaign_created_at, campaign_state, created_at, updated_at)
                VALUES (?, 101, 55, '점심식당', 'KOREAN', 37.4979, 127.0276, 1000, 'ALL', 'AGE_20S,AGE_30S',
                    'ALL', 'PERCENT', 20, '12:00', '14:00', 30, NOW(6), 'SERVABLE', NOW(6), NOW(6))
                """, TODAY);
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(new MemberInfo(MEMBER_ID, "점심러",
                MemberStatus.ACTIVE, true, true, true, BigDecimal.valueOf(37.4979), BigDecimal.valueOf(127.0276),
                Gender.FEMALE, AgeGroup.AGE_30S)));
        // 예약액 10만 원, 단가 10원이라 노출 상한은 10,500 회다. 한 건 모자란 데까지 채워 둔다
        when(budgetQueryService.getProgress(any(), any(), any()))
                .thenReturn(List.of(new BudgetProgress(101L, 100_000, 10, 10_000, 50_000, 0.2)));
        redisTemplate.opsForHash().put(COUNTER_KEY, "101", "10499");
        mockMvc.perform(get("/v1/feed").with(user(new CustomUserDetails(MEMBER_ID, Role.MEMBER))))
                .andExpect(jsonPath("$.data.cards[0].campaignId").value(101));

        send(body(serveId, 101L), Role.MEMBER).andExpect(status().isAccepted());

        redisTemplate.delete("adserving:servedSession:" + MEMBER_ID);
        mockMvc.perform(get("/v1/feed").with(user(new CustomUserDetails(MEMBER_ID, Role.MEMBER))))
                .andExpect(jsonPath("$.data.status").value("NO_CANDIDATE"));
    }
}
