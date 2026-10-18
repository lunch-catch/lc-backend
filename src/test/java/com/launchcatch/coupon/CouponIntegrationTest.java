package com.launchcatch.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.adserving.contract.CandidateStateUpdater;
import com.launchcatch.coupon.service.CouponIssuanceService;
import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.contract.AccessTokenValidationPolicy;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.coupon.contract.CouponStatsQueryService;
import com.launchcatch.coupon.contract.DailyCampaignCouponStats;
import com.launchcatch.coupon.contract.MemberCouponStats;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponUsageHistoryResponse;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponInventoryRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.coupon.service.CouponCommandService;
import com.launchcatch.coupon.contract.CouponIssueOpenedEvent;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.coupon.contract.CouponRedeemedEvent;
import com.launchcatch.coupon.contract.CouponExpiredEvent;
import com.launchcatch.coupon.service.CouponLookUpService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreQueryService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "coupon.daily-limit=3", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(CouponIntegrationTest.FunnelRecorder.class)
class CouponIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final CustomUserDetails MEMBER = new CustomUserDetails(17L, Role.MEMBER);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired CouponLookUpService couponLookUpService;
    @Autowired CouponCommandService couponCommandService;
    @Autowired CouponIssuanceService couponIssuanceService;
    @Autowired CouponStatsQueryService couponStats;
    @Autowired FunnelRecorder funnel;

    @MockitoSpyBean Clock clock;
    @MockitoBean OwnerStatusQuery ownerStatusQuery;
    @MockitoBean CampaignQueryService campaignQueryService;
    @MockitoBean StoreQueryService stores;
    @MockitoSpyBean WishlistQueryService wishes;
    @MockitoBean CandidateStateUpdater candidates;
    @MockitoBean(name = "ownerAccessTokenPolicy") AccessTokenValidationPolicy ownerTokenPolicy;
    @MockitoSpyBean CouponQrImageEncoder imageEncoder;
    @MockitoSpyBean MemberQueryService members;
    @MockitoSpyBean CouponEventRepository events;
    @MockitoSpyBean CouponInventoryRepository inventories;
    @MockitoSpyBean MemberCouponRepository memberCouponRepository;
    @MockitoSpyBean CouponUsageHistoryRepository histories;

    @BeforeEach
    void setUpClock() {
        funnel.received.clear();
        doReturn(List.of()).when(wishes).findWishedCampaignIds(any());
        doReturn(false).when(wishes).isWished(any(), any());
        when(clock.withZone(ClockConfig.ZONE))
                .thenReturn(Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ClockConfig.ZONE));
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-10T03:00:00Z"));
    }

    @Nested
    @DisplayName("분석용 쿠폰 집계 조회")
    class Stats {

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history");
            jdbcTemplate.update("DELETE FROM member_coupon");
            jdbcTemplate.update("DELETE FROM coupon_inventory");
            jdbcTemplate.update("DELETE FROM coupon_event");
        }

        @Test
        void 회차_수를_오픈_수로_세고_발급은_모든_상태를_만료는_EXPIRED만_센다() {
            event(81, 91, TODAY, "ACTIVE");
            event(82, 92, TODAY, "PAUSED");
            event(83, 93, TODAY, "ENDED");
            event(84, 94, TODAY, "SCHEDULED");
            event(85, 91, TODAY.minusDays(1), "ACTIVE");
            coupon(101, 81, 17, "USED");
            coupon(102, 81, 18, "EXPIRED");
            coupon(103, 81, 19, "ISSUED");
            coupon(104, 81, 20, "REMOVED");
            coupon(105, 85, 17, "USED");
            usage(101, TODAY.atTime(12, 0), 1500);
            usage(105, TODAY.atTime(12, 1), 1500);

            assertThat(couponStats.dailyCampaignStats(TODAY)).containsExactly(
                    new DailyCampaignCouponStats(91, 1, 4, 2, 1),
                    new DailyCampaignCouponStats(92, 1, 0, 0, 0),
                    new DailyCampaignCouponStats(93, 1, 0, 0, 0),
                    new DailyCampaignCouponStats(94, 1, 0, 0, 0));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM member_coupon WHERE member_coupon_id=103", String.class)).isEqualTo("ISSUED");
        }

        @Test
        void 사용_수는_회차의_영업일과_별개로_KST_사용일의_양끝_경계를_적용한다() {
            event(81, 91, TODAY.minusDays(1), "ENDED");
            coupon(101, 81, 17, "USED");
            coupon(102, 81, 18, "USED");
            coupon(103, 81, 19, "USED");
            coupon(104, 81, 20, "USED");
            usage(101, TODAY.atStartOfDay().minusNanos(1000), 100);
            usage(102, TODAY.atStartOfDay(), 200);
            usage(103, TODAY.plusDays(1).atStartOfDay().minusNanos(1000), 300);
            usage(104, TODAY.plusDays(1).atStartOfDay(), 400);

            assertThat(couponStats.dailyCampaignStats(TODAY))
                    .containsExactly(new DailyCampaignCouponStats(91, 0, 0, 2, 0));
        }

        @Test
        void 캠페인이_다르면_발급과_사용과_만료_수가_섞이지_않는다() {
            event(81, 91, TODAY, "ACTIVE");
            event(82, 92, TODAY, "ACTIVE");
            coupon(101, 81, 17, "USED");
            coupon(102, 82, 17, "USED");
            coupon(103, 82, 18, "EXPIRED");
            usage(101, TODAY.atTime(12, 0), 100);
            usage(102, TODAY.atTime(12, 0), 200);

            assertThat(couponStats.dailyCampaignStats(TODAY)).containsExactly(
                    new DailyCampaignCouponStats(91, 1, 1, 1, 0),
                    new DailyCampaignCouponStats(92, 1, 2, 1, 1));
        }

        @Test
        void 그날_회차와_사용_기록이_없으면_빈_목록이다() {
            event(81, 91, TODAY.minusDays(1), "ACTIVE");
            coupon(101, 81, 17, "EXPIRED");

            assertThat(couponStats.dailyCampaignStats(TODAY)).isEmpty();
        }

        @Test
        void 종료_시각이_지나도_저장_상태가_EXPIRED인_쿠폰만_만료로_집계한다() {
            LocalDate day = TODAY.minusDays(3);
            event(81, 91, day, "ENDED");
            coupon(101, 81, 17, "EXPIRED");
            coupon(102, 81, 18, "ISSUED");
            coupon(103, 81, 19, "REMOVED");

            assertThat(couponStats.dailyCampaignStats(day))
                    .containsExactly(new DailyCampaignCouponStats(91, 1, 3, 0, 1));
            assertThat(jdbcTemplate.queryForList(
                    "SELECT status FROM member_coupon ORDER BY member_coupon_id", String.class))
                    .containsExactly("EXPIRED", "ISSUED", "REMOVED");
        }

        @Test
        void 회원_단건_집계는_모든_날짜의_확정_할인액을_더하며_다른_회원은_제외한다() {
            event(81, 91, TODAY.minusDays(2), "ENDED");
            event(82, 91, TODAY.minusDays(1), "ENDED");
            event(83, 91, TODAY, "ACTIVE");
            coupon(101, 81, 17, "USED");
            coupon(102, 82, 17, "USED");
            coupon(103, 83, 17, "USED");
            coupon(104, 83, 18, "USED");
            usage(101, TODAY.minusDays(2).atTime(12, 0), 1_500_000_000);
            usage(102, TODAY.minusDays(1).atTime(12, 0), 1_500_000_000);
            usage(103, TODAY.atTime(12, 0), 0);
            usage(104, TODAY.atTime(12, 0), 5000);

            assertThat(couponStats.memberStats(17))
                    .isEqualTo(new MemberCouponStats(3, 3_000_000_000L));
        }

        @Test
        void 회원의_사용_기록이_없으면_누적_집계는_0이다() {
            event(81, 91, TODAY, "ACTIVE");
            coupon(101, 81, 17, "ISSUED");

            assertThat(couponStats.memberStats(17)).isEqualTo(new MemberCouponStats(0, 0));
        }

        @Test
        void 회원의_발급_기록이_없어도_0인_단건을_반환한다() {
            assertThat(couponStats.memberStats(17)).isEqualTo(new MemberCouponStats(0, 0));
        }

        private void event(long id, long campaignId, LocalDate day, String status) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                        usable_start_at, usable_end_at, status, store_id, store_name,
                        discount_target_type, discount_type, discount_value, created_at, updated_at)
                    VALUES (?, ?, ?, 100, ?, ?, ?, 10, '테스트 매장', 'ALL', 'AMOUNT', 1000, ?, ?)
                    """, id, campaignId, day, day.atStartOfDay(), day.plusDays(3).atStartOfDay(), status,
                    day.atStartOfDay(), day.atStartOfDay());
        }

        private void coupon(long id, long eventId, long memberId, String status) {
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                        status, usable_start_at, usable_end_at, created_at, updated_at)
                    SELECT ?, coupon_event_id, ?, UNHEX(LPAD(HEX(?), 32, '0')), ?,
                           usable_start_at, usable_end_at, usable_start_at, usable_start_at
                      FROM coupon_event WHERE coupon_event_id = ?
                    """, id, memberId, id, status, eventId);
        }

        private void usage(long couponId, LocalDateTime createdAt, int discount) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (?, 10, ?, ?, ?)
                    """, couponId, discount, createdAt, createdAt);
        }
    }

    @Nested
    @DisplayName("쿠폰 발급")
    class Issue {

        private final AtomicReference<Instant> time = new AtomicReference<>();

        @BeforeEach
        void setUp() {
            time.set(Instant.parse("2026-10-10T03:00:00Z"));
            when(clock.withZone(ClockConfig.ZONE)).thenAnswer(invocation -> Clock.fixed(time.get(), ClockConfig.ZONE));
            when(clock.getZone()).thenReturn(ClockConfig.ZONE);
            when(clock.instant()).thenAnswer(invocation -> time.get());
            doAnswer(invocation -> Optional.of(new MemberInfo(
                    invocation.getArgument(0), "회원", MemberStatus.ACTIVE, false, false, false, null, null, null, null)))
                    .when(members).findById(any());
            when(wishes.isWished(any(), any())).thenReturn(true);
            insertEvent(81L, 3);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history");
            jdbcTemplate.update("DELETE FROM member_coupon");
            jdbcTemplate.update("DELETE FROM coupon_inventory");
            jdbcTemplate.update("DELETE FROM coupon_daily_limit");
            jdbcTemplate.update("DELETE FROM coupon_event");
        }

        @Test
        void 마지막_재고를_커밋한_뒤에만_트랜잭션_밖에서_소진을_알린다() {
            couponCommandService.issue(17L, 81L);
            couponCommandService.issue(18L, 81L);
            verifyNoInteractions(candidates);
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                assertConsistent(3);
                assertThat(invocation.<Long>getArgument(0)).isEqualTo(81L);
                assertThat(invocation.<LocalDate>getArgument(1)).isEqualTo(TODAY);
                assertThat(invocation.<LocalDateTime>getArgument(2)).isEqualTo(TODAY.atTime(12, 0));
                return null;
            }).when(candidates).markSoldOut(any(), any(), any());

            couponCommandService.issue(19L, 81L);

            verify(candidates).markSoldOut(81L, TODAY, TODAY.atTime(12, 0));
        }

        @Test
        void 소진_반영이_실패해도_HTTP_발급은_성공하고_후속_소진_거절에서_복구한다() throws Exception {
            couponCommandService.issue(17L, 81L);
            couponCommandService.issue(18L, 81L);
            doThrow(new DataAccessResourceFailureException("candidate unavailable"))
                    .when(candidates).markSoldOut(any(), any(), any());
            mockMvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(19L, Role.MEMBER)))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":81}"))
                    .andExpect(status().isCreated());
            assertConsistent(3);
            doNothing().when(candidates).markSoldOut(any(), any(), any());
            time.set(Instant.parse("2026-10-10T03:05:00Z"));

            mockMvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(20L, Role.MEMBER)))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":81}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON-006"));

            verify(candidates, times(2))
                    .markSoldOut(81L, TODAY, TODAY.atTime(12, 0));
            assertConsistent(3);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM coupon_daily_limit WHERE member_id=20", Integer.class)).isZero();
        }

        @Test
        void 찜하지_않은_회원은_HTTP_403이며_발급_상태를_변경하지_않는다() throws Exception {
            when(wishes.isWished(17L, 81L)).thenReturn(false);
            mockMvc.perform(post("/v1/coupons").with(user(MEMBER))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":81}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-004"));
            assertConsistent(0);
            verifyNoInteractions(candidates);
        }

        @Test
        void 찜_조회_장애는_HTTP_500이며_발급을_롤백한다() throws Exception {
            when(wishes.isWished(17L, 81L)).thenThrow(new DataAccessResourceFailureException("wishlist unavailable"));
            mockMvc.perform(post("/v1/coupons").with(user(MEMBER))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":81}"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));
            assertConsistent(0);
            verifyNoInteractions(candidates);
        }

        @Test
        void 마지막_발급의_커밋이_실패하면_소진을_통보하지_않는다() {
            jdbcTemplate.update("DELETE FROM coupon_inventory WHERE sequence_no > 1");
            var failure = new DataAccessResourceFailureException("commit failed");
            var real = mockingDetails(memberCouponRepository).getMockCreationSettings().getDefaultAnswer();
            doAnswer(invocation -> {
                Object saved = real.answer(invocation);
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw failure;
                            }
                        });
                return saved;
            }).when(memberCouponRepository).saveAndFlush(any());

            assertThatThrownBy(() -> couponCommandService.issue(17L, 81L)).isSameAs(failure);
            assertConsistent(0);
            assertThat(funnel.received).isEmpty();
            verifyNoInteractions(candidates);
        }

        @Test
        void 조정_메서드는_호출자의_트랜잭션_안에서_발급하지_않는다() {
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction ->
                    couponCommandService.issue(17L, 81L)))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertConsistent(0);
            verifyNoInteractions(candidates);
        }

        @Test
        void HTTP_발급은_201과_저장된_한국_시각을_반환한다() throws Exception {
            mockMvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
                            .contentType("application/json").content("{\"eventId\":81}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.memberCouponId").isNumber())
                    .andExpect(jsonPath("$.data.issueTime").value("2026-10-10T12:00:00+09:00"));
            assertConsistent(1);
            assertThat(funnel.received).hasSize(1);
            CouponIssuedEvent issued = (CouponIssuedEvent) funnel.received.getFirst();
            assertThat(issued.couponEventId()).isEqualTo(81);
            assertThat(issued.memberCouponId()).isEqualTo(jdbcTemplate.queryForObject(
                    "SELECT member_coupon_id FROM member_coupon", Long.class));
            assertThat(jdbcTemplate.queryForObject("SELECT sequence_no FROM coupon_inventory WHERE status='ISSUED'", Integer.class))
                    .isEqualTo(1);
        }

        @Test
        void 발급_트랜잭션이_롤백하면_분석_리스너가_수신하지_않는다() {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                couponIssuanceService.issue(17L, 81L);
                assertThat(funnel.received).isEmpty();
                transaction.setRollbackOnly();
            });
            assertThat(funnel.received).isEmpty();
            verifyNoInteractions(candidates);
            assertConsistent(0);
        }

        @Test
        void HTTP로_발급한_쿠폰의_사용_이벤트는_같은_회원_쿠폰_ID를_싣는다() throws Exception {
            mockMvc.perform(post("/v1/coupons").with(user(MEMBER)).contentType("application/json")
                    .content("{\"eventId\":81}")).andExpect(status().isCreated());
            CouponIssuedEvent issued = (CouponIssuedEvent) funnel.received.getFirst();
            long couponId = issued.memberCouponId();
            mockMvc.perform(post("/v1/coupons/" + couponId + ":generateQr").with(user(MEMBER)))
                    .andExpect(status().isOk());
            UUID token = memberCouponRepository.findById(couponId).orElseThrow().getQrToken();
            when(ownerStatusQuery.findCurrentStatus(9L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            when(stores.canManageStore(9L, 10L)).thenReturn(true);
            when(stores.findAllById(List.of(10L))).thenReturn(List.of(
                    new StoreInfo(10L, "매장", "https://example.test/store.png", 37.5, 127.0)));

            mockMvc.perform(post("/v1/owner/coupon-usages").with(user(new CustomUserDetails(9L, Role.OWNER)))
                    .contentType("application/json").content("""
                            {"qrToken":"%s","latitude":37.5,"longitude":127.0}
                            """.formatted(token))).andExpect(status().isCreated());

            assertThat(funnel.received).hasSize(2);
            CouponRedeemedEvent redeemed = (CouponRedeemedEvent) funnel.received.getLast();
            assertThat(redeemed.memberCouponId()).isEqualTo(issued.memberCouponId());
            assertThat(redeemed.couponEventId()).isEqualTo(issued.couponEventId());
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM member_coupon", String.class)).isEqualTo("USED");
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"eventId\":null}", "{\"eventId\":0}", "{\"eventId\":-1}",
                "{\"eventId\":81.2}", "{\"eventId\":\"81\"}", "{\"eventId\":true}",
                "{\"eventId\":9223372036854775808}"})
        void 잘못된_요청은_발급하지_않는다(String body) throws Exception {
            mockMvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
                            .contentType("application/json").content(body)).andExpect(status().isBadRequest());
            assertConsistent(0);
        }

        @Test
        void 인증과_회원_역할이_필요하다() throws Exception {
            mockMvc.perform(post("/v1/coupons").contentType("application/json").content("{\"eventId\":81}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.OWNER)))
                            .contentType("application/json").content("{\"eventId\":81}"))
                    .andExpect(status().isForbidden());
            assertConsistent(0);
        }

        @Test
        void 서로_다른_회원_다섯_명이_동시에_요청해도_재고_세_장만_발급한다() throws Exception {
            List<String> results = concurrently(IntStream.range(0, 5).mapToObj(i -> new long[] {100L + i, 81L}).toList());
            assertThat(results.stream().filter("CREATED"::equals)).hasSize(3);
            assertThat(results).allMatch(code -> code.equals("CREATED") || code.equals("COUPON-006"));
            assertConsistent(3);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT coupon_code) FROM member_coupon", Integer.class))
                    .isEqualTo(3);
        }

        @Test
        void 같은_회원의_동시_재요청은_한_장만_발급한다() throws Exception {
            List<String> results = concurrently(IntStream.range(0, 5).mapToObj(i -> new long[] {17L, 81L}).toList());
            assertThat(results.stream().filter("CREATED"::equals)).hasSize(1);
            assertThat(results.stream().filter("COUPON-005"::equals)).hasSize(4);
            assertConsistent(1);
        }

        @Test
        void 여러_이벤트에_동시에_요청해도_한_회원은_세_장까지만_발급한다() throws Exception {
            for (long id = 82; id <= 85; id++) {
                insertEvent(id, 1);
            }
            List<String> results = concurrently(IntStream.rangeClosed(81, 85).mapToObj(i -> new long[] {17L, i}).toList());
            assertThat(results.stream().filter("CREATED"::equals)).hasSize(3);
            assertThat(results.stream().filter("COUPON-007"::equals)).hasSize(2);
            assertConsistent(3);
        }

        @Test
        void 삭제한_이벤트에서_다시_발급할_수_없고_다른_이벤트는_발급할_수_있다() {
            var issued = couponCommandService.issue(17L, 81L);
            couponCommandService.deleteCoupon(17L, issued.memberCouponId());
            assertError(17L, 81L, CouponErrorCode.EVENT_ALREADY_ISSUED);
            insertEvent(82L, 1);
            couponCommandService.issue(17L, 82L);
            assertThat(jdbcTemplate.queryForObject("SELECT SUM(issued_count) FROM coupon_daily_limit", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_inventory WHERE status='ISSUED'", Integer.class))
                    .isEqualTo(2);
        }

        @Test
        void 삭제와_다른_이벤트_발급이_겹쳐도_카운터_변경을_잃지_않는다() throws Exception {
            var first = couponCommandService.issue(17L, 81L);
            insertEvent(82L, 1);
            insertEvent(83L, 1);
            insertEvent(84L, 1);
            couponCommandService.issue(17L, 82L);
            couponCommandService.issue(17L, 83L);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> deletion = executor.submit(() -> {
                    await(start);
                    couponCommandService.deleteCoupon(17L, first.memberCouponId());
                });
                Future<String> issuance = executor.submit(() -> {
                    await(start);
                    return issue(17L, 84L);
                });
                Future<String> repeat = executor.submit(() -> {
                    await(start);
                    return issue(17L, 81L);
                });
                start.countDown();
                deletion.get(5, TimeUnit.SECONDS);
                String issued = issuance.get(5, TimeUnit.SECONDS);
                assertThat(issued).isIn("CREATED", "COUPON-007");
                assertThat(repeat.get(5, TimeUnit.SECONDS)).isEqualTo("COUPON-005");
                int expected = issued.equals("CREATED") ? 3 : 2;
                assertThat(jdbcTemplate.queryForObject("SELECT SUM(issued_count) FROM coupon_daily_limit", Integer.class))
                        .isEqualTo(expected);
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_coupon WHERE status='ISSUED'", Integer.class))
                        .isEqualTo(expected);
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_inventory WHERE status='ISSUED'", Integer.class))
                        .isEqualTo(expected + 1);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"event", "inventory", "flush"})
        void 각_저장_단계의_실패는_카운터와_재고와_쿠폰을_모두_롤백한다(String stage) {
            var failure = new DataAccessResourceFailureException("발급 저장 실패");
            if (stage.equals("event")) {
                doThrow(failure).when(events).findByIdForUpdate(81L);
            } else if (stage.equals("inventory")) {
                doThrow(failure).when(inventories)
                        .findFirstByCouponEventIdAndStatusOrderBySequenceNoAsc(81L, CouponInventoryStatus.AVAILABLE);
            } else {
                doAnswer(invocation -> {
                    entityManager.persist(invocation.getArgument(0));
                    entityManager.flush();
                    throw failure;
                }).when(memberCouponRepository).saveAndFlush(any());
            }
            assertThatThrownBy(() -> couponCommandService.issue(17L, 81L)).isSameAs(failure);
            assertConsistent(0);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_daily_limit", Integer.class)).isZero();
            assertThat(funnel.received).isEmpty();
        }

        @Test
        void DB_조회가_실패하면_공통_서버_오류를_반환하고_롤백한다() throws Exception {
            doThrow(new QueryTimeoutException("DB 조회 실패")).when(events).findByIdForUpdate(81L);
            mockMvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
                            .contentType("application/json").content("{\"eventId\":81}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-001"))
                    .andExpect(header().doesNotExist("Retry-After"));
            assertConsistent(0);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_daily_limit", Integer.class)).isZero();
        }

        @Test
        void 잠금_대기_중_발급_시간이_끝나면_발급하지_않는다() throws Exception {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject("SELECT coupon_event_id FROM coupon_event WHERE coupon_event_id=81 FOR UPDATE", Long.class);
                    locked.countDown();
                    await(release);
                }));
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                Future<String> request = executor.submit(() -> issue(17L, 81L));
                try {
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(800);
                    while (lockWaits() == 0
                            && System.nanoTime() < deadline) {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                    }
                    assertThat(lockWaits()).isPositive();
                    time.set(Instant.parse("2026-10-10T05:00:00Z"));
                } finally {
                    release.countDown();
                }
                assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("COUPON-002");
                holder.get(5, TimeUnit.SECONDS);
            }
            assertConsistent(0);
        }

        private List<String> concurrently(List<long[]> requests) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<String>> results = new ArrayList<>();
                for (long[] request : requests) {
                    results.add(executor.submit(() -> {
                        await(start);
                        return issue(request[0], request[1]);
                    }));
                }
                start.countDown();
                List<String> codes = new ArrayList<>();
                for (var result : results) {
                    codes.add(result.get(15, TimeUnit.SECONDS));
                }
                return codes;
            }
        }

        private String issue(long memberId, long eventId) {
            try {
                couponCommandService.issue(memberId, eventId);
                return "CREATED";
            } catch (CouponException exception) {
                return exception.getErrorCode().getCode();
            }
        }

        private void assertError(long memberId, long eventId, CouponErrorCode code) {
            assertThatThrownBy(() -> couponCommandService.issue(memberId, eventId)).isInstanceOfSatisfying(CouponException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        }

        private void assertConsistent(int expected) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_coupon", Integer.class)).isEqualTo(expected);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_inventory WHERE status='ISSUED'", Integer.class))
                    .isEqualTo(expected);
            assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(SUM(issued_count),0) FROM coupon_daily_limit", Integer.class))
                    .isEqualTo(expected);
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 요청 준비가 끝나지 않았다.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }

        private int lockWaits() {
            return Integer.parseInt(jdbcTemplate.queryForMap("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_current_waits'")
                    .get("Value").toString());
        }

        private void insertEvent(long eventId, int quantity) {
            LocalDateTime now = TODAY.atTime(12, 0);
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                        usable_start_at, usable_end_at, status, store_id, store_name,
                        discount_target_type, discount_type, discount_value, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 10, '발급 시험 매장', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, eventId, eventId, TODAY, quantity, TODAY.atTime(11, 0), TODAY.atTime(14, 0), now, now);
            jdbcTemplate.batchUpdate("""
                    INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                        usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (?, ?, UNHEX(MD5(?)), 'AVAILABLE', ?, ?, ?, ?)
                    """, IntStream.rangeClosed(1, quantity).mapToObj(sequence -> new Object[] {
                        eventId, sequence, eventId + ":" + sequence, TODAY.atTime(11, 0), TODAY.atTime(14, 0), now, now
                    }).toList());
        }
    }

    @Nested
    @DisplayName("매장 사용 이력 조회")
    @Transactional
    class UsageHistoryQuery {

        private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
        private static final LocalDate TO = LocalDate.of(2026, 9, 30);
        private static final LocalDateTime USE_TIME = TO.atTime(12, 30, 0, 123456000);
        private static final String PATH = "/v1/owner/stores/10/coupon-usages";

        @BeforeEach
        void setUp() {
            when(ownerStatusQuery.findCurrentStatus(17L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            insertHistory(5001L, 10L, FROM.atStartOfDay().minusNanos(1000), 1000);
            insertHistory(5002L, 10L, FROM.atStartOfDay(), 2000);
            insertHistory(5003L, 10L, USE_TIME, 2700);
            insertHistory(5004L, 10L, USE_TIME, 3000);
            insertHistory(5005L, 10L, TO.plusDays(1).atStartOfDay(), 4000);
            insertHistory(5006L, 20L, USE_TIME.plusMinutes(1), 5000);
            insertHistory(5007L, 10L, FROM.minusYears(2).atStartOfDay(), 1500);
            entityManager.clear();
        }

        @Test
        void 요청한_매장만_조회하며_시작일_시작은_포함하고_종료일_다음날_시작은_제외한다() {
            var response = couponLookUpService.getUsages(17L, 10L, FROM, TO, 20, null);

            assertThat(response.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L, 5002L);
            assertThat(response.nextPageToken()).isNull();
            assertThat(response.items().get(1).discountAmount()).isEqualTo(2700);
            assertThat(response.items().get(1).useTime()).isEqualTo(USE_TIME.atOffset(ZoneOffset.ofHours(9)));
        }

        @Test
        void 같은_사용_시각의_이력도_페이지에서_누락하지_않으며_새_이력은_기존_커서에_영향을_주지_않는다() {
            var first = couponLookUpService.getUsages(17L, 10L, FROM, TO, 1, null);
            insertHistory(5008L, 10L, USE_TIME.plusMinutes(1), 2500);

            var second = couponLookUpService.getUsages(17L, 10L, FROM, TO, 1, first.nextPageToken());
            var third = couponLookUpService.getUsages(17L, 10L, FROM, TO, 1, second.nextPageToken());

            assertThat(first.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5004L);
            assertThat(second.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5003L);
            assertThat(third.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5002L);
            assertThat(third.nextPageToken()).isNull();
        }

        @Test
        void 같은_날_조회에서는_그날의_사용_이력만_반환한다() {
            var singleDay = couponLookUpService.getUsages(17L, 10L, TO, TO, 100, null);

            assertThat(singleDay.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L);
        }

        @Test
        void 조회_기간_길이에_상한_없이_여러_해의_사용_이력을_반환한다() {
            var multipleYears = couponLookUpService.getUsages(17L, 10L, FROM.minusYears(3), TO, 100, null);

            assertThat(multipleYears.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L, 5002L, 5001L, 5007L);
        }

        @Test
        void 이력이_없는_매장은_빈_목록을_반환한다() {
            var response = couponLookUpService.getUsages(17L, 30L, FROM, TO, 20, null);

            assertThat(response.items()).isEmpty();
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        void 점주는_HTTP로_매장_사용_이력을_조회한다() throws Exception {
            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                            .with(user(new CustomUserDetails(17L, Role.OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items.length()").value(3))
                    .andExpect(jsonPath("$.data.items[0].usageHistoryId").value(5004))
                    .andExpect(jsonPath("$.data.items[1].discountAmount").value(2700))
                    .andExpect(jsonPath("$.data.nextPageToken").isEmpty());
        }

        @ParameterizedTest
        @EnumSource(value = OwnerAccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        void ACTIVE가_아닌_점주는_HTTP_조회를_거절한다(OwnerAccountStatus state) throws Exception {
            when(ownerStatusQuery.findCurrentStatus(17L)).thenReturn(Optional.of(state));

            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                            .with(user(new CustomUserDetails(17L, Role.OWNER))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @Test
        void 인증된_점주_ID로_현재_상태를_검증한다() throws Exception {
            when(ownerStatusQuery.findCurrentStatus(17L)).thenReturn(Optional.empty());
            when(ownerStatusQuery.findCurrentStatus(71L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));

            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                            .with(user(new CustomUserDetails(71L, Role.OWNER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(3));
        }

        @Test
        void 점주가_없으면_HTTP_조회를_거절한다() throws Exception {
            when(ownerStatusQuery.findCurrentStatus(17L)).thenReturn(Optional.empty());

            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                            .with(user(new CustomUserDetails(17L, Role.OWNER))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @Test
        void 점주_상태_조회_장애는_서버_오류로_반환한다() throws Exception {
            when(ownerStatusQuery.findCurrentStatus(17L))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("owner status query failed"));

            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                            .with(user(new CustomUserDetails(17L, Role.OWNER))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-001"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
        void 점주_외_역할의_HTTP_조회를_차단한다(Role role) throws Exception {
            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                            .with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @ParameterizedTest
        @CsvSource({
                "0, 2026-09-01, 2026-09-30, 20, COMMON-002",
                "-1, 2026-09-01, 2026-09-30, 20, COMMON-002",
                "invalid, 2026-09-01, 2026-09-30, 20, COMMON-003",
                "10, , 2026-09-30, 20, COMMON-003",
                "10, 2026-09-01, , 20, COMMON-003",
                "10, 2026-09-01, 2026-09-30, 0, COMMON-002",
                "10, 2026-09-01, 2026-09-30, 101, COMMON-002"
        })
        void 잘못된_매장_ID와_필수_날짜_및_페이지_크기는_HTTP에서_거절한다(
                String storeId, String from, String to, String pageSize, String code) throws Exception {
            var request = get("/v1/owner/stores/{storeId}/coupon-usages", storeId)
                    .param("pageSize", pageSize).with(user(new CustomUserDetails(17L, Role.OWNER)));
            if (from != null) {
                request.param("from", from);
            }
            if (to != null) {
                request.param("to", to);
            }

            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(code));
        }

        @Test
        void 조회는_쿠폰과_사용_이력_및_재고와_발급_횟수를_변경하지_않는다() {
            var coupons = jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id");
            var histories = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history ORDER BY coupon_usage_history_id");
            var inventories = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY coupon_inventory_id");
            var limits = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id");

            couponLookUpService.getUsages(17L, 10L, FROM, TO, 1, null);

            assertThat(jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id"))
                    .usingRecursiveComparison().isEqualTo(coupons);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history ORDER BY coupon_usage_history_id"))
                    .isEqualTo(histories);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY coupon_inventory_id"))
                    .usingRecursiveComparison().isEqualTo(inventories);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id"))
                    .isEqualTo(limits);
        }

        @Test
        void Swagger는_사용_이력의_성공_응답_계약을_설명한다() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/v1/owner/stores/{storeId}/coupon-usages'].get.responses['200'].content['*/*'].example.data.items[0].discountAmount")
                            .value(3000))
                    .andExpect(jsonPath("$.paths['/v1/owner/stores/{storeId}/coupon-usages'].get.responses['200'].content['*/*'].example.data.nextPageToken")
                            .isEmpty());
        }

        @Test
        void 미인증_HTTP_조회는_인증_오류를_반환한다() throws Exception {
            mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        // 사용 이력 조회에 사용할 이벤트와 사용 완료 쿠폰, 확정 사용 결과를 DB에 저장한다.
        private void insertHistory(Long id, Long storeId, LocalDateTime useTime, int discountAmount) {
            entityManager.createNativeQuery("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (:id, :id, :businessDate, 100, :startAt, :endAt, 'ACTIVE', :storeId, '런치식당',
                            'ALL', 'AMOUNT', 3000, :useTime, :useTime)
                    """).setParameter("id", id).setParameter("businessDate", useTime.toLocalDate())
                    .setParameter("startAt", useTime.minusHours(1)).setParameter("endAt", useTime.plusHours(1))
                    .setParameter("storeId", storeId).setParameter("useTime", useTime).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code, qr_version,
                            status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (:id, :id, 17, :code, 0, 'USED', :startAt, :endAt, :useTime, :useTime)
                    """).setParameter("id", id).setParameter("code", UUID.randomUUID())
                    .setParameter("startAt", useTime.minusHours(1)).setParameter("endAt", useTime.plusHours(1))
                    .setParameter("useTime", useTime).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO coupon_usage_history (coupon_usage_history_id, member_coupon_id, store_id,
                            discount_amount, created_at, updated_at)
                    VALUES (:id, :id, :storeId, :discountAmount, :useTime, :useTime)
                    """).setParameter("id", id).setParameter("storeId", storeId)
                    .setParameter("discountAmount", discountAmount).setParameter("useTime", useTime).executeUpdate();
        }
    }

    @Nested
    @DisplayName("쿠폰 목록 조회")
    @Transactional
    class ListQuery {

        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

        @BeforeEach
        void setUp() {
            insertCoupon(1001L, 17L, "EXPIRED", NOW.minusHours(3), NOW.minusHours(2), NOW.minusHours(3));
            insertCoupon(1002L, 17L, "ISSUED", NOW.minusHours(1), NOW, NOW.minusHours(2));
            insertCoupon(1003L, 17L, "ISSUED", NOW, NOW.plusHours(1), NOW.minusHours(1).plusNanos(123456000));
            insertCoupon(1004L, 17L, "ISSUED", NOW.plusHours(1), NOW.plusHours(2), NOW.minusHours(1).plusNanos(123456000));
            insertCoupon(1005L, 17L, "USED", NOW.minusHours(4), NOW.minusHours(3), NOW.minusHours(4));
            insertCoupon(1006L, 17L, "REMOVED", NOW, NOW.plusHours(1), NOW);
            insertCoupon(1007L, 18L, "ISSUED", NOW, NOW.plusHours(1), NOW);
            entityManager.createNativeQuery("""
                    INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (1005, 10, 3000, :usedAt, :usedAt)
                    """).setParameter("usedAt", NOW.minusHours(4).plusMinutes(30)).executeUpdate();
            entityManager.clear();
        }

        @Test
        void 본인_쿠폰만_발급_시각과_ID_내림차순으로_조회하고_삭제된_쿠폰을_제외한다() {
            CursorPageResponse<CouponDetailResponse> response = couponLookUpService.getCoupons(17L, null, 20, null);

            assertThat(response.items()).extracting(CouponDetailResponse::memberCouponId)
                    .containsExactly(1004L, 1003L, 1002L, 1001L, 1005L);
            assertThat(response.nextPageToken()).isNull();
            assertThat(response.items().get(2).state().name()).isEqualTo("EXPIRED");
            assertThat(response.items().getLast().discountAmount()).isEqualTo(3000);
        }

        static Stream<Arguments> filters() {
            return Stream.of(
                    Arguments.of("usable = true", List.of(1003L)),
                    Arguments.of("state = \"ISSUED\"", List.of(1004L, 1003L)),
                    Arguments.of("state = \"EXPIRED\"", List.of(1002L, 1001L)),
                    Arguments.of("state = \"USED\"", List.of(1005L)));
        }

        @Test
        void 종료_시각에_도달한_미사용_쿠폰은_목록_조회에서_DB_상태도_만료로_변경한다() {
            couponLookUpService.getCoupons(17L, null, 20, null);
            entityManager.flush();
            entityManager.clear();

            List<?> states = entityManager.createNativeQuery("""
                    SELECT status FROM member_coupon ORDER BY member_coupon_id
                    """).getResultList();

            assertThat(states).isEqualTo(List.of("EXPIRED", "EXPIRED", "ISSUED", "ISSUED", "USED", "REMOVED", "ISSUED"));
        }

        @ParameterizedTest
        @MethodSource("filters")
        void 필터는_조회_시각_상태로_적용한다(String filter, List<Long> expectedIds) {
            CursorPageResponse<CouponDetailResponse> response = couponLookUpService.getCoupons(17L, filter, 20, null);
            assertThat(response.items()).extracting(CouponDetailResponse::memberCouponId)
                    .containsExactlyElementsOf(expectedIds);
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        void 필터를_먼저_적용하여_사용_시작_전_쿠폰이_많아도_사용_가능_쿠폰의_페이지를_채운다() {
            CursorPageResponse<CouponDetailResponse> response = couponLookUpService.getCoupons(17L, "usable = true", 1, null);

            assertThat(response.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1003L);
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        void 발급_시각이_같아도_페이지에서_중복이나_누락이_없고_새_쿠폰은_이전_커서에_영향을_주지_않는다() {
            CursorPageResponse<CouponDetailResponse> first = couponLookUpService.getCoupons(17L, null, 1, null);
            insertCoupon(1008L, 17L, "ISSUED", NOW, NOW.plusHours(1), NOW);

            CursorPageResponse<CouponDetailResponse> second = couponLookUpService.getCoupons(17L, null, 2, first.nextPageToken());
            CursorPageResponse<CouponDetailResponse> third = couponLookUpService.getCoupons(17L, null, 2, second.nextPageToken());

            assertThat(first.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1004L);
            assertThat(second.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1003L, 1002L);
            assertThat(third.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1001L, 1005L);
            assertThat(third.nextPageToken()).isNull();
        }

        @Test
        void 쿠폰_없는_회원에게는_빈_목록을_반환한다() {
            assertThat(couponLookUpService.getCoupons(19L, null, 20, null)).isEqualTo(CursorPageResponse.of(List.of(), null));
        }

        @Test
        void 목록_API는_기본_페이지_크기와_성공_봉투와_한국_시각을_사용한다() throws Exception {
            mockMvc.perform(get("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.items.length()").value(5))
                    .andExpect(jsonPath("$.data.items[0].memberCouponId").value(1004))
                    .andExpect(jsonPath("$.data.items[0].usable").value(false))
                    .andExpect(jsonPath("$.data.items[1].usable").value(true))
                    .andExpect(jsonPath("$.data.items[0].issueTime").value("2026-10-10T11:00:00.123456+09:00"))
                    .andExpect(jsonPath("$.data.nextPageToken").isEmpty());
        }

        @Test
        void 인증_없이는_목록을_조회할_수_없다() throws Exception {
            mockMvc.perform(get("/v1/coupons"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"OWNER", "ADMIN", "SUPER_ADMIN"})
        void 회원_외_역할은_목록_조회를_거절한다(Role role) throws Exception {
            mockMvc.perform(get("/v1/coupons").with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, 101})
        void 페이지_크기_범위_오류는_공통_입력_오류로_반환한다(int pageSize) throws Exception {
            mockMvc.perform(get("/v1/coupons").param("pageSize", String.valueOf(pageSize))
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 페이지_크기_타입_오류는_공통_해석_오류로_반환한다() throws Exception {
            mockMvc.perform(get("/v1/coupons").param("pageSize", "abc")
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        }

        @Test
        void 지원하지_않는_필터는_공통_입력_오류로_반환한다() throws Exception {
            mockMvc.perform(get("/v1/coupons").param("filter", "usable = false")
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        void 양수가_아닌_단건_쿠폰_ID는_공통_입력_오류로_반환한다(String id) throws Exception {
            mockMvc.perform(get("/v1/coupons/" + id)
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 잘못된_커서는_공통_입력_오류로_반환한다() throws Exception {
            mockMvc.perform(get("/v1/coupons").param("pageToken", "invalid-token")
                            .with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"/v1/coupons", "/v1/coupons/1005"})
        void 사용_이력이_없는_사용_완료_쿠폰은_목록과_단건에서_동일한_정합성_오류로_반환한다(String path) throws Exception {
            entityManager.createNativeQuery("DELETE FROM coupon_usage_history WHERE member_coupon_id = 1005")
                    .executeUpdate();

            mockMvc.perform(get(path).with(user(new CustomUserDetails(17L, Role.MEMBER))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-001"))
                    .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        void 필터를_바꾸거나_전체로_전환하면_이전_커서를_거절한다() throws Exception {
            String token = couponLookUpService.getCoupons(17L, "state = \"ISSUED\"", 1, null).nextPageToken();

            mockMvc.perform(get("/v1/coupons").param("pageToken", token).with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
            mockMvc.perform(get("/v1/coupons").param("filter", "state = \"USED\"").param("pageToken", token)
                            .with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 반환하지_않은_쿠폰은_만료로_변경하지_않는다() {
            couponLookUpService.getCoupons(17L, null, 1, null);
            entityManager.flush();

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM member_coupon WHERE member_coupon_id = 1002", String.class))
                    .isEqualTo("ISSUED");
        }

        // 목록 조회에 사용할 이벤트 스냅샷과 회원 쿠폰을 DB에 저장한다.
        private void insertCoupon(Long id, Long memberId, String state, LocalDateTime start, LocalDateTime end,
                LocalDateTime issueTime) {
            entityManager.createNativeQuery("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (:id, :id, :businessDate, 100, :start, :end, 'ACTIVE', 10, '런치식당', 'ALL', 'AMOUNT',
                            3000, :issueTime, :issueTime)
                    """).setParameter("id", id).setParameter("businessDate", NOW.toLocalDate())
                    .setParameter("start", start).setParameter("end", end).setParameter("issueTime", issueTime)
                    .executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code, qr_version,
                            status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (:id, :id, :memberId, :code, 0, :state, :start, :end, :issueTime, :issueTime)
                    """).setParameter("id", id).setParameter("memberId", memberId)
                    .setParameter("code", UUID.randomUUID()).setParameter("state", state)
                    .setParameter("start", start).setParameter("end", end).setParameter("issueTime", issueTime)
                    .executeUpdate();
        }
    }

    @Nested
    @DisplayName("쿠폰 통계 조회")
    @Transactional
    class SummaryQuery {

        private static final String PATH = "/v1/coupons/summary";

        @Test
        void 본인의_발급_이력이_없으면_다른_회원의_이력과_관계없이_모두_0이다() throws Exception {
            insertCoupon(1001L, 18L, "USED");
            insertUsage(1001L, 1500);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.receivedCouponCount").value(0))
                    .andExpect(jsonPath("$.data.usedCouponCount").value(0))
                    .andExpect(jsonPath("$.data.totalDiscountAmount").value(0));
        }

        @Test
        void 사용_이력이_없어도_삭제한_쿠폰을_포함한_발급_수를_반환한다() throws Exception {
            insertCoupon(1001L, 17L, "ISSUED");
            insertCoupon(1002L, 17L, "REMOVED");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.receivedCouponCount").value(2))
                    .andExpect(jsonPath("$.data.usedCouponCount").value(0))
                    .andExpect(jsonPath("$.data.totalDiscountAmount").value(0));
        }

        @Test
        void 모든_쿠폰을_발급_수에_포함하고_확정_사용_결과를_변경없이_집계한다() throws Exception {
            insertCoupon(1001L, 17L, "ISSUED");
            insertCoupon(1002L, 17L, "EXPIRED");
            insertCoupon(1003L, 17L, "REMOVED");
            insertCoupon(1004L, 17L, "USED", 0);
            insertUsage(1004L, 0);
            insertCoupon(1005L, 17L, "USED");
            insertUsage(1005L, 1500);
            insertCoupon(1006L, 17L, "USED");
            insertUsage(1006L, 1500);
            insertCoupon(1007L, 18L, "USED");
            insertUsage(1007L, 1500);
            var couponsBefore = jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id");
            var usagesBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history ORDER BY coupon_usage_history_id");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.receivedCouponCount").value(6))
                    .andExpect(jsonPath("$.data.usedCouponCount").value(3))
                    .andExpect(jsonPath("$.data.totalDiscountAmount").value(3000));

            entityManager.flush();
            assertThat(jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id"))
                    .usingRecursiveComparison()
                    .isEqualTo(couponsBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history ORDER BY coupon_usage_history_id"))
                    .isEqualTo(usagesBefore);
        }

        @Test
        void 각_할인액이_int_범위여도_합계는_잘리지_않는다() throws Exception {
            insertCoupon(1001L, 17L, "USED", 3_000_000_000L);
            insertUsage(1001L, 1_500_000_000);
            insertCoupon(1002L, 17L, "USED", 3_000_000_000L);
            insertUsage(1002L, 1_500_000_000);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.receivedCouponCount").value(2))
                    .andExpect(jsonPath("$.data.usedCouponCount").value(2))
                    .andExpect(jsonPath("$.data.totalDiscountAmount").value(3_000_000_000L));
        }

        @Test
        void 인증되지_않은_요청은_로그인이_필요하다() throws Exception {
            mockMvc.perform(get(PATH))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "MEMBER")
        void 회원이_아닌_역할은_조회할_수_없다(Role role) throws Exception {
            mockMvc.perform(get(PATH).with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        private void insertCoupon(long id, long memberId, String status) {
            insertCoupon(id, memberId, status, 3000);
        }

        // 정률 할인 메뉴의 스냅샷과 회원 쿠폰을 실제 저장 제약에 맞춰 준비한다.
        private void insertCoupon(long id, long memberId, String status, long menuPrice) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_event
                        (coupon_event_id, campaign_id, business_date, issue_quantity,
                         usable_start_at, usable_end_at, status, store_id, store_name,
                         discount_target_type, discount_type, discount_value,
                         target_menu_id, target_menu_name, target_menu_price, target_menu_discounted_price,
                         created_at, updated_at)
                    VALUES (?, ?, '2026-10-09', 100, '2026-10-09 11:00:00', '2026-10-09 13:00:00',
                            'ACTIVE', 10, '테스트 매장', 'MENU', 'PERCENT', 50, 1, '테스트 메뉴', ?, ?,
                            '2026-10-09 00:00:00', '2026-10-09 00:00:00')
                    """, id, id, menuPrice, menuPrice / 2);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon
                        (member_coupon_id, coupon_event_id, member_id, coupon_code, qr_version, status,
                         usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (?, ?, ?, UNHEX(LPAD(HEX(?), 32, '0')), 0, ?,
                            '2026-10-09 11:00:00', '2026-10-09 13:00:00',
                            '2026-10-09 11:00:00', '2026-10-09 11:00:00')
                    """, id, id, memberId, id, status);
        }

        private void insertUsage(long memberCouponId, int discountAmount) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history
                        (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (?, 10, ?, '2026-10-09 12:00:00', '2026-10-09 12:00:00')
                    """, memberCouponId, discountAmount);
        }
    }

    @Nested
    @DisplayName("일일 발급 한도 조회")
    @Transactional
    class IssuanceLimitQuery {

        private static final String PATH = "/v1/coupons/issuance-limit";

        @Test
        void 인증된_회원의_오늘_기록만_조회한다() throws Exception {
            insertDailyLimit(17L, TODAY, 2);
            insertDailyLimit(18L, TODAY, 3);
            insertDailyLimit(17L, TODAY.minusDays(1), 3);
            var before = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.date").value("2026-10-10"))
                    .andExpect(jsonPath("$.data.issuedCount").value(2))
                    .andExpect(jsonPath("$.data.dailyLimit").value(3))
                    .andExpect(jsonPath("$.data.dailyLimitReached").value(false));

            entityManager.flush();
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id"))
                    .isEqualTo(before);
        }

        @Test
        void 발급_기록이_없으면_0건을_반환하고_기록을_만들지_않는다() throws Exception {
            insertDailyLimit(17L, TODAY.minusDays(1), 3);
            insertDailyLimit(18L, TODAY, 3);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.date").value("2026-10-10"))
                    .andExpect(jsonPath("$.data.issuedCount").value(0))
                    .andExpect(jsonPath("$.data.dailyLimit").value(3))
                    .andExpect(jsonPath("$.data.dailyLimitReached").value(false));

            entityManager.flush();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM coupon_daily_limit
                     WHERE member_id = ? AND business_date = ?
                    """, Integer.class, 17L, TODAY))
                    .isZero();
        }

        @Test
        void 인증되지_않은_요청은_로그인이_필요하다() throws Exception {
            mockMvc.perform(get(PATH))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "MEMBER")
        void 회원이_아닌_역할은_조회할_수_없다(Role role) throws Exception {
            mockMvc.perform(get(PATH).with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        // 발급 횟수는 회원과 영업일별 기록에 저장하며 다른 도메인의 데이터는 필요하지 않다.
        private void insertDailyLimit(long memberId, LocalDate businessDate, int issuedCount) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit
                        (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (?, ?, ?, '2026-10-10 12:00:00', '2026-10-10 12:00:00')
                    """, memberId, businessDate, issuedCount);
        }
    }

    @Nested
    @DisplayName("쿠폰 단건 조회")
    class DetailQuery {

        private static final String PATH = "/v1/coupons/1001";
        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

        @BeforeEach
        void setUp() {
            setTime(Instant.parse("2026-10-10T03:00:00Z"));
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, store_image_url,
                            discount_target_type, discount_type, discount_value, target_menu_id, target_menu_name,
                            target_menu_price, target_menu_discounted_price, created_at, updated_at)
                    VALUES (81, 81, ?, 100, ?, ?, 'ACTIVE', 10, '런치식당', 'https://images.example.org/menu-v1.jpg?size=640&variant=2',
                            'MENU', 'AMOUNT', 3000, 701, '숯불갈비 정식', 12000, 9000, ?, ?)
                    """, TODAY, NOW.minusHours(1), NOW.plusHours(2), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                            usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (81, 1, UNHEX('00112233445566778899AABBCCDDEEFF'), 'ISSUED', ?, ?, ?, ?)
                    """, NOW.minusHours(1), NOW.plusHours(2), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                            qr_version, qr_token, qr_expires_at, status, usable_start_at, usable_end_at,
                            created_at, updated_at)
                    VALUES (1001, 81, 17, UNHEX('00112233445566778899AABBCCDDEEFF'), 3,
                            UNHEX('FFEEDDCCBBAA99887766554433221100'), ?, 'ISSUED', ?, ?, ?, ?)
                    """, NOW.plusMinutes(1), NOW.minusHours(1), NOW.plusHours(2), NOW.minusMinutes(90), NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (17, ?, 1, ?, ?)
                    """, TODAY, NOW, NOW);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history");
            jdbcTemplate.update("DELETE FROM member_coupon");
            jdbcTemplate.update("DELETE FROM coupon_inventory");
            jdbcTemplate.update("DELETE FROM coupon_daily_limit");
            jdbcTemplate.update("DELETE FROM coupon_event");
        }

        @Test
        void 본인_쿠폰의_스냅샷과_한국_시각을_반환하고_내부_식별자와_QR은_노출하지_않는다() throws Exception {
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {"code":"SUCCESS","message":"요청이 성공적으로 처리되었습니다.","data":{
                              "memberCouponId":1001,"storeId":10,"storeName":"런치식당",
                              "storeImageUrl":"https://images.example.org/menu-v1.jpg?size=640&variant=2",
                              "discountTargetType":"MENU","discountType":"AMOUNT","discountValue":3000,
                              "targetMenu":{"menuId":701,"name":"숯불갈비 정식","price":12000,"discountedPrice":9000},
                              "state":"ISSUED","usable":true,"issueTime":"2026-10-10T10:30:00+09:00",
                              "usableStartTime":"2026-10-10T11:00:00+09:00",
                              "usableEndTime":"2026-10-10T14:00:00+09:00","useTime":null,"discountAmount":null
                            }}
                            """))
                    .andExpect(jsonPath("$.data.eventId").doesNotExist())
                    .andExpect(jsonPath("$.data.qrToken").doesNotExist());

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @Test
        void 이미지_URL이_255자를_넘어도_쿼리_문자열까지_그대로_저장하고_반환한다() throws Exception {
            String imageUrl = "https://images.example.org/" + "a".repeat(350) + "/menu.jpg?quality=90";
            jdbcTemplate.update("UPDATE coupon_event SET store_image_url = ? WHERE coupon_event_id = 81", imageUrl);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeImageUrl").value(imageUrl));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT store_image_url FROM coupon_event WHERE coupon_event_id = 81", String.class)).isEqualTo(imageUrl);
        }

        @Test
        void 만료를_저장해도_QR과_발급_횟수와_재고와_사용_이력은_유지한다() throws Exception {
            setTime(Instant.parse("2026-10-10T05:00:00Z"));
            Map<String, Object> before = findCoupon();
            var limitBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
            var inventoryBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory");
            var usagesBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("EXPIRED"))
                    .andExpect(jsonPath("$.data.usable").value(false));

            assertThat(findCoupon().get("status")).isEqualTo("EXPIRED");
            assertThat(findCoupon()).usingRecursiveComparison().ignoringFields("status", "updated_at").isEqualTo(before);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limitBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory"))
                    .usingRecursiveComparison().isEqualTo(inventoryBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history")).isEqualTo(usagesBefore);
        }

        @ParameterizedTest
        @CsvSource({
                "2026-10-10T01:59:59.999999Z, ISSUED, false",
                "2026-10-10T02:00:00Z, ISSUED, true",
                "2026-10-10T04:59:59.999999Z, ISSUED, true",
                "2026-10-10T05:00:00Z, EXPIRED, false"
        })
        void 사용_시작은_포함하고_종료부터_만료를_저장한다(Instant now, String state, boolean usable) throws Exception {
            setTime(now);

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value(state))
                    .andExpect(jsonPath("$.data.usable").value(usable));

            assertThat(findCoupon().get("status")).isEqualTo(state);
        }

        @Test
        void 만료_쿠폰을_다시_조회하면_추가로_변경하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'EXPIRED' WHERE member_coupon_id = 1001");
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("EXPIRED"))
                    .andExpect(jsonPath("$.data.usable").value(false));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 3000})
        void 사용_완료_쿠폰은_기간이_지나도_사용_상태와_확정_결과를_반환한다(int discountAmount) throws Exception {
            setTime(Instant.parse("2026-10-10T06:00:00Z"));
            jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (1001, 10, ?, ?, ?)
                    """, discountAmount, NOW, NOW);
            Map<String, Object> before = findCoupon();
            var usagesBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history");

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.state").value("USED"))
                    .andExpect(jsonPath("$.data.usable").value(false))
                    .andExpect(jsonPath("$.data.useTime").value("2026-10-10T12:00:00+09:00"))
                    .andExpect(jsonPath("$.data.discountAmount").value(discountAmount));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history")).isEqualTo(usagesBefore);
        }

        @Test
        void 삭제된_본인_쿠폰은_404로_거절하고_만료로_변경하지_않는다() throws Exception {
            setTime(Instant.parse("2026-10-10T06:00:00Z"));
            jdbcTemplate.update("UPDATE member_coupon SET status = 'REMOVED' WHERE member_coupon_id = 1001");
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COUPON-001"));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @Test
        void 사용_완료_이력이_누락되면_정합성_오류_코드로_500을_반환한다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get(PATH).with(user(MEMBER)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-001"))
                    .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1001", "9999"})
        void 남의_쿠폰과_없는_ID는_동일한_403으로_거절한다(String id) throws Exception {
            setTime(Instant.parse("2026-10-10T06:00:00Z"));
            Map<String, Object> before = findCoupon();

            mockMvc.perform(get("/v1/coupons/" + id).with(user(new CustomUserDetails(18L, Role.MEMBER))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-011"));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        void 양수가_아닌_ID는_400으로_거절한다(String id) throws Exception {
            mockMvc.perform(get("/v1/coupons/" + id).with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "9223372036854775808"})
        void 정수로_해석할_수_없는_ID는_400으로_거절한다(String id) throws Exception {
            mockMvc.perform(get("/v1/coupons/" + id).with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        }

        @Test
        void 인증되지_않은_요청은_401로_거절한다() throws Exception {
            mockMvc.perform(get(PATH))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
        void MEMBER_외의_역할은_403으로_거절한다(Role role) throws Exception {
            mockMvc.perform(get(PATH).with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @Test
        void 만료_상태_저장이_실패하면_변경을_롤백하고_성공_응답을_반환하지_않는다() throws Exception {
            setTime(Instant.parse("2026-10-10T05:00:00Z"));
            Map<String, Object> before = findCoupon();
            jdbcTemplate.execute("""
                    ALTER TABLE member_coupon ADD CONSTRAINT chk_test_expiry_failure CHECK (status <> 'EXPIRED')
                    """);
            try {
                mockMvc.perform(get(PATH).with(user(MEMBER)))
                        .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));

                assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
            } finally {
                jdbcTemplate.execute("ALTER TABLE member_coupon DROP CHECK chk_test_expiry_failure");
            }
        }

        @Test
        void 만료_조회가_먼저_잠그면_삭제는_만료_저장_뒤에_진행한다() throws Exception {
            setTime(Instant.parse("2026-10-10T05:00:00Z"));
            CountDownLatch started = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<?> deletion = new TransactionTemplate(transactionManager).execute(transaction -> {
                    assertThat(couponLookUpService.getDetail(17L, 1001L).state()).isEqualTo(MemberCouponStatus.EXPIRED);
                    Future<?> result = executor.submit(() -> {
                        started.countDown();
                        mockMvc.perform(delete(PATH).with(user(MEMBER))).andExpect(status().isNoContent());
                        return null;
                    });
                    assertBlocked(result, started);
                    return result;
                });

                deletion.get(5, TimeUnit.SECONDS);
            }

            assertThat(findCoupon().get("status")).isEqualTo("REMOVED");
            assertThat(jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_daily_limit", Integer.class)).isZero();
        }

        @Test
        void 삭제가_먼저_잠그면_조회는_삭제_저장_뒤에_404로_거절한다() throws Exception {
            setTime(Instant.parse("2026-10-10T05:00:00Z"));
            CountDownLatch started = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<?> query = new TransactionTemplate(transactionManager).execute(transaction -> {
                    couponCommandService.deleteCoupon(17L, 1001L);
                    Future<?> result = executor.submit(() -> {
                        started.countDown();
                        mockMvc.perform(get(PATH).with(user(MEMBER)))
                                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COUPON-001"));
                        return null;
                    });
                    assertBlocked(result, started);
                    return result;
                });

                query.get(5, TimeUnit.SECONDS);
            }

            assertThat(findCoupon().get("status")).isEqualTo("REMOVED");
            assertThat(findCoupon().get("qr_token")).isNull();
        }

        @Nested
        @DisplayName("목록 조회의 만료 저장과 동시 요청")
        class ListMutationQuery {

            @Test
            void 만료_저장은_QR과_발급_횟수와_재고와_사용_이력을_보존한다() throws Exception {
                setTime(Instant.parse("2026-10-10T05:00:00Z"));
                Map<String, Object> before = findCoupon();
                var limit = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
                var inventory = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory");
                var usages = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history");

                mockMvc.perform(get("/v1/coupons").with(user(MEMBER)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items[0].state").value("EXPIRED"))
                        .andExpect(jsonPath("$.data.items[0].usable").value(false))
                        .andExpect(jsonPath("$.data.items[0].storeImageUrl")
                                .value("https://images.example.org/menu-v1.jpg?size=640&variant=2"));

                assertThat(findCoupon().get("status")).isEqualTo("EXPIRED");
                assertThat(findCoupon()).usingRecursiveComparison().ignoringFields("status", "updated_at").isEqualTo(before);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limit);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory"))
                        .usingRecursiveComparison().isEqualTo(inventory);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history")).isEqualTo(usages);
            }

            @Test
            void 만료_저장이_실패하면_변경을_롤백한다() throws Exception {
                setTime(Instant.parse("2026-10-10T05:00:00Z"));
                Map<String, Object> before = findCoupon();
                jdbcTemplate.execute("ALTER TABLE member_coupon ADD CONSTRAINT chk_test_list_expiry CHECK (status <> 'EXPIRED')");
                try {
                    mockMvc.perform(get("/v1/coupons").with(user(MEMBER)))
                            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));
                    assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
                } finally {
                    jdbcTemplate.execute("ALTER TABLE member_coupon DROP CHECK chk_test_list_expiry");
                }
            }

            @Test
            void 목록이_먼저_잠그면_삭제는_만료_저장_뒤에_진행한다() throws Exception {
                setTime(Instant.parse("2026-10-10T05:00:00Z"));
                CountDownLatch started = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Future<?> deletion = new TransactionTemplate(transactionManager).execute(transaction -> {
                        assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst().state())
                                .isEqualTo(MemberCouponStatus.EXPIRED);
                        Future<?> result = executor.submit(() -> {
                            started.countDown();
                            couponCommandService.deleteCoupon(17L, 1001L);
                            return null;
                        });
                        assertBlocked(result, started);
                        return result;
                    });
                    deletion.get(5, TimeUnit.SECONDS);
                }
                assertThat(findCoupon().get("status")).isEqualTo("REMOVED");
                assertThat(jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_daily_limit", Integer.class)).isZero();
            }

            @Test
            void 삭제가_먼저_잠그면_목록은_삭제된_쿠폰을_제외한다() throws Exception {
                CountDownLatch started = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Future<?> query = new TransactionTemplate(transactionManager).execute(transaction -> {
                        couponCommandService.deleteCoupon(17L, 1001L);
                        Future<?> result = executor.submit(() -> {
                            started.countDown();
                            assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items()).isEmpty();
                            return null;
                        });
                        assertBlocked(result, started);
                        return result;
                    });
                    query.get(5, TimeUnit.SECONDS);
                }
                assertThat(findCoupon().get("status")).isEqualTo("REMOVED");
            }

            @Test
            void 사용_저장이_먼저_잠그면_목록은_확정_사용_결과를_반환한다() throws Exception {
                setTime(Instant.parse("2026-10-10T05:00:00Z"));
                CountDownLatch started = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Future<?> query = new TransactionTemplate(transactionManager).execute(transaction -> {
                        saveUsage();
                        Future<?> result = executor.submit(() -> {
                            started.countDown();
                            var item = couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst();
                            assertThat(item.state()).isEqualTo(MemberCouponStatus.USED);
                            assertThat(item.discountAmount()).isEqualTo(3000);
                            return null;
                        });
                        assertBlocked(result, started);
                        return result;
                    });
                    query.get(5, TimeUnit.SECONDS);
                }
                assertThat(findCoupon().get("status")).isEqualTo("USED");
            }

            @Test
            void 목록이_먼저_잠그면_사용_저장은_목록_트랜잭션_뒤에_진행한다() throws Exception {
                CountDownLatch started = new CountDownLatch(1);
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Future<?> usage = new TransactionTemplate(transactionManager).execute(transaction -> {
                        assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst().state())
                                .isEqualTo(MemberCouponStatus.ISSUED);
                        Future<?> result = executor.submit(() -> {
                            started.countDown();
                            new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> saveUsage());
                            return null;
                        });
                        assertBlocked(result, started);
                        return result;
                    });
                    usage.get(5, TimeUnit.SECONDS);
                }
                assertThat(findCoupon().get("status")).isEqualTo("USED");
                assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst().discountAmount()).isEqualTo(3000);
            }

            // 사용 API 구현 전이므로 같은 쿠폰 행을 잠그는 실제 DB 트랜잭션으로 상태와 사용 이력을 함께 저장한다.
            private void saveUsage() {
                jdbcTemplate.queryForList("SELECT member_coupon_id FROM member_coupon WHERE member_coupon_id = 1001 FOR UPDATE");
                jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
                jdbcTemplate.update("""
                        INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                        VALUES (1001, 10, 3000, ?, ?)
                        """, NOW, NOW);
            }
        }

        private void assertBlocked(Future<?> result, CountDownLatch started) {
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            assertThatThrownBy(() -> result.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        }

        private void setTime(Instant instant) {
            when(clock.withZone(ClockConfig.ZONE)).thenReturn(Clock.fixed(instant, ClockConfig.ZONE));
            when(clock.getZone()).thenReturn(ClockConfig.ZONE);
            when(clock.instant()).thenReturn(instant);
        }

        private Map<String, Object> findCoupon() {
            return jdbcTemplate.queryForMap("SELECT * FROM member_coupon WHERE member_coupon_id = 1001");
        }
    }

    @Nested
    @DisplayName("쿠폰 삭제")
    class DeleteCoupon {

        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);

        @BeforeEach
        void setUp() {
            when(clock.getZone()).thenReturn(ClockConfig.ZONE);
            when(clock.instant()).thenReturn(Instant.parse("2026-10-10T03:00:00Z"));
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (81, 81, ?, 100, ?, ?, 'ACTIVE', 10, '런치식당', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, NOW.toLocalDate(), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                            usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (81, 1, UNHEX('00112233445566778899AABBCCDDEEFF'), 'ISSUED', ?, ?, ?, ?)
                    """, NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                            qr_version, qr_token, qr_expires_at, status, usable_start_at, usable_end_at,
                            created_at, updated_at)
                    VALUES (1001, 81, 17, UNHEX('00112233445566778899AABBCCDDEEFF'), 3,
                            UNHEX('FFEEDDCCBBAA99887766554433221100'), ?, 'ISSUED', ?, ?, ?, ?)
                    """, NOW.plusMinutes(1), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (17, ?, 1, ?, ?)
                    """, NOW.toLocalDate(), NOW, NOW);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history");
            jdbcTemplate.update("DELETE FROM member_coupon");
            jdbcTemplate.update("DELETE FROM coupon_inventory");
            jdbcTemplate.update("DELETE FROM coupon_daily_limit");
            jdbcTemplate.update("DELETE FROM coupon_event");
        }

        @ParameterizedTest
        @EnumSource(value = MemberCouponStatus.class, names = {"ISSUED", "EXPIRED"})
        void 삭제는_204를_반환하고_발급_횟수를_차감하며_QR을_무효화한다(MemberCouponStatus state)
                throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = ? WHERE member_coupon_id = 1001", state.name());
            Map<String, Object> before = findCoupon();
            var inventoryBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory");

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));

            Map<String, Object> coupon = findCoupon();
            assertThat(coupon.get("status")).isEqualTo("REMOVED");
            assertThat(coupon.get("qr_version")).isEqualTo(4);
            assertThat(coupon.get("qr_token")).isNull();
            assertThat(coupon.get("qr_expires_at")).isNull();
            assertThat(coupon).usingRecursiveComparison()
                    .ignoringFields("status", "qr_version", "qr_token", "qr_expires_at", "updated_at")
                    .isEqualTo(before);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory"))
                    .usingRecursiveComparison().isEqualTo(inventoryBefore);
            mockMvc.perform(get("/v1/coupons/issuance-limit").with(user(MEMBER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.issuedCount").value(0));
            mockMvc.perform(get("/v1/coupons/summary").with(user(MEMBER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.receivedCouponCount").value(1));
        }

        @Test
        void 삭제를_재요청해도_204를_반환하고_추가로_변경하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 2 WHERE member_id = 17");
            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER))).andExpect(status().isNoContent());
            Map<String, Object> removed = findCoupon();

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(removed);
            assertThat(findIssuedCount()).isEqualTo(1);
        }

        @Test
        void 사용_완료_쿠폰은_409를_반환하고_사용_이력과_QR을_변경하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (1001, 10, 3000, ?, ?)
                    """, NOW, NOW);
            Map<String, Object> before = findCoupon();

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON-012"));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
            assertThat(jdbcTemplate.queryForObject("SELECT discount_amount FROM coupon_usage_history", Integer.class))
                    .isEqualTo(3000);
            assertThat(findIssuedCount()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1001", "9999"})
        void 다른_회원_쿠폰과_없는_ID는_동일한_403으로_거절한다(String id) throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'USED' WHERE member_coupon_id = 1001");
            Map<String, Object> before = findCoupon();

            mockMvc.perform(delete("/v1/coupons/" + id).with(user(new CustomUserDetails(18L, Role.MEMBER))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-011"));

            assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
            assertThat(findIssuedCount()).isEqualTo(1);
        }

        @Test
        void 다른_회원이_이미_삭제된_쿠폰을_요청해도_소유권_검사를_생략하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET status = 'REMOVED' WHERE member_coupon_id = 1001");

            mockMvc.perform(delete("/v1/coupons/1001").with(user(new CustomUserDetails(18L, Role.MEMBER))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-011"));
        }

        @Test
        void 인증_없이는_삭제할_수_없다() throws Exception {
            mockMvc.perform(delete("/v1/coupons/1001"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
            assertThat(findCoupon().get("status")).isEqualTo("ISSUED");
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = {"OWNER", "ADMIN", "SUPER_ADMIN"})
        void 회원_외_역할은_삭제할_수_없다(Role role) throws Exception {
            mockMvc.perform(delete("/v1/coupons/1001").with(user(new CustomUserDetails(17L, role))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
            assertThat(findCoupon().get("status")).isEqualTo("ISSUED");
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        void 양수가_아닌_쿠폰_ID는_공통_입력_오류로_반환한다(String id) throws Exception {
            mockMvc.perform(delete("/v1/coupons/" + id).with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 정수로_해석할_수_없는_쿠폰_ID는_공통_해석_오류로_반환한다() throws Exception {
            mockMvc.perform(delete("/v1/coupons/abc").with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        }

        @Test
        void 같은_쿠폰을_동시에_삭제해도_발급_횟수는_한_번만_차감한다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 2 WHERE member_id = 17");
            deleteConcurrently(1001L,
                    "SELECT member_coupon_id FROM member_coupon WHERE member_coupon_id = 1001 FOR UPDATE");

            Map<String, Object> coupon = findCoupon();
            assertThat(coupon.get("status")).isEqualTo("REMOVED");
            assertThat(coupon.get("qr_version")).isEqualTo(4);
            assertThat(coupon.get("qr_token")).isNull();
            assertThat(coupon.get("qr_expires_at")).isNull();
            assertThat(findIssuedCount()).isEqualTo(1);
        }

        @Test
        void 같은_날_발급한_서로_다른_쿠폰을_동시에_삭제하면_각각_차감한다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 2 WHERE member_id = 17");
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (82, 82, ?, 100, ?, ?, 'ACTIVE', 10, '런치식당', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, NOW.toLocalDate(), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                            qr_version, status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (1002, 82, 17, UNHEX('11112233445566778899AABBCCDDEEFF'), 0, 'ISSUED', ?, ?, ?, ?)
                    """, NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);

            deleteConcurrently(1002L,
                    "SELECT coupon_daily_limit_id FROM coupon_daily_limit WHERE member_id = 17 FOR UPDATE");

            assertThat(jdbcTemplate.queryForList("SELECT status FROM member_coupon ORDER BY member_coupon_id", String.class))
                    .containsExactly("REMOVED", "REMOVED");
            assertThat(findIssuedCount()).isZero();
        }

        @Test
        void 과거에_발급한_쿠폰을_오늘_삭제해도_발급받은_날짜의_횟수만_줄인다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET created_at = ? WHERE member_coupon_id = 1001", NOW.minusDays(1));
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 3 WHERE member_id = 17");
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (17, ?, 2, ?, ?)
                    """, NOW.toLocalDate().minusDays(1), NOW.minusDays(1), NOW.minusDays(1));

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isNoContent());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT issued_count FROM coupon_daily_limit WHERE member_id = 17 AND business_date = ?",
                    Integer.class, NOW.toLocalDate().minusDays(1))).isEqualTo(1);
            mockMvc.perform(get("/v1/coupons/issuance-limit").with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issuedCount").value(3))
                    .andExpect(jsonPath("$.data.dailyLimitReached").value(true));
        }

        @Test
        void 삭제로_횟수가_줄면_일일_발급_한도에_다시_여유가_생긴다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_daily_limit SET issued_count = 3 WHERE member_id = 17");

            mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/v1/coupons/issuance-limit").with(user(MEMBER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.issuedCount").value(2))
                    .andExpect(jsonPath("$.data.dailyLimitReached").value(false));
        }

        private void deleteConcurrently(long secondCouponId, String lockQuery) throws Exception {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> deleteTogether(1001L, ready, start));
                var second = executor.submit(() -> deleteTogether(secondCouponId, ready, start));
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                        jdbcTemplate.queryForObject(lockQuery, Long.class);
                        try {
                            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        start.countDown();
                        assertThatThrownBy(() -> first.get(200, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                        assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                    });
                } finally {
                    start.countDown();
                }

                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            }

        }

        @Test
        void 쿠폰_저장이_실패하면_횟수_차감과_상태와_QR_변경을_함께_롤백한다() throws Exception {
            Map<String, Object> before = findCoupon();
            var limitBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
            jdbcTemplate.execute("""
                    ALTER TABLE member_coupon ADD CONSTRAINT chk_test_delete_failure CHECK (status <> 'REMOVED')
                    """);
            try {
                mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("COMMON-001"));

                assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limitBefore);
            } finally {
                jdbcTemplate.execute("ALTER TABLE member_coupon DROP CHECK chk_test_delete_failure");
            }
        }

        private Void deleteTogether(long couponId, CountDownLatch ready, CountDownLatch start) throws Exception {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(delete("/v1/coupons/" + couponId).with(user(MEMBER)))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
            return null;
        }

        @Test
        void 횟수_저장이_실패해도_쿠폰_상태와_QR과_횟수_차감을_함께_롤백한다() throws Exception {
            Map<String, Object> before = findCoupon();
            var limitBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
            jdbcTemplate.execute("""
                    ALTER TABLE coupon_daily_limit ADD CONSTRAINT chk_test_count_failure CHECK (issued_count > 0)
                    """);
            try {
                mockMvc.perform(delete("/v1/coupons/1001").with(user(MEMBER)))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("COMMON-001"));

                assertThat(findCoupon()).usingRecursiveComparison().isEqualTo(before);
                assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limitBefore);
            } finally {
                jdbcTemplate.execute("ALTER TABLE coupon_daily_limit DROP CHECK chk_test_count_failure");
            }
        }

        // 삭제 전후의 상태와 QR 정보를 비교할 회원 쿠폰을 DB에서 조회한다.
        private Map<String, Object> findCoupon() {
            return jdbcTemplate.queryForMap("SELECT * FROM member_coupon WHERE member_coupon_id = 1001");
        }

        private int findIssuedCount() {
            return jdbcTemplate.queryForObject(
                    "SELECT issued_count FROM coupon_daily_limit WHERE member_id = 17 AND business_date = ?",
                    Integer.class, NOW.toLocalDate());
        }
    }

    @Nested
    @DisplayName("QR 생성과 갱신")
    class GenerateQr {

        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);
        private final AtomicReference<Instant> currentTime = new AtomicReference<>();

        private static final String QR_PATH = "/v1/coupons/1001:generateQr";

        @BeforeEach
        void setUp() {
            currentTime.set(NOW.atZone(ClockConfig.ZONE).toInstant());
            when(clock.withZone(ClockConfig.ZONE))
                    .thenAnswer(invocation -> Clock.fixed(currentTime.get(), ClockConfig.ZONE));
            jdbcTemplate.update("""
                    INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at)
                    VALUES (17, 'coupon-qr-test', '점심헌터', 'ACTIVE', ?, ?)
                    """, NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (81, 81, ?, 100, ?, ?, 'ACTIVE', 10, '런치식당', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, NOW.toLocalDate(), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                            qr_version, qr_token, qr_expires_at, status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (1001, 81, 17, UNHEX('00112233445566778899AABBCCDDEEFF'), 3,
                            UNHEX('FFEEDDCCBBAA99887766554433221100'), ?, 'ISSUED', ?, ?, ?, ?)
                    """, NOW.plusSeconds(30), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                            usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (81, 1, UNHEX('00112233445566778899AABBCCDDEEFF'), 'ISSUED', ?, ?, ?, ?)
                    """, NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (17, ?, 1, ?, ?)
                    """, NOW.toLocalDate(), NOW, NOW);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM member_coupon");
            jdbcTemplate.update("DELETE FROM coupon_inventory");
            jdbcTemplate.update("DELETE FROM coupon_daily_limit");
            jdbcTemplate.update("DELETE FROM coupon_event");
            jdbcTemplate.update("DELETE FROM member WHERE member_id = 17");
        }

        @Test
        void QR_API는_PNG와_60초_만료와_no_store를_반환하고_갱신을_저장한다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_event SET status = 'PAUSED' WHERE coupon_event_id = 81");
            var inventoryBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory");
            var limitBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
            var usageBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history");
            mockMvc.perform(post(QR_PATH).with(user(MEMBER)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.qrImageBytes").isNotEmpty())
                    .andExpect(jsonPath("$.data.expireTime").value("2026-10-10T12:01:00+09:00"));
            Map<String, Object> first = coupon();

            mockMvc.perform(post(QR_PATH).with(user(MEMBER))).andExpect(status().isOk());

            assertThat(first.get("qr_version")).isEqualTo(4);
            assertThat(coupon().get("qr_version")).isEqualTo(5);
            assertThat(coupon().get("qr_token")).isNotEqualTo(first.get("qr_token"));
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory"))
                    .usingRecursiveComparison().isEqualTo(inventoryBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit"))
                    .usingRecursiveComparison().isEqualTo(limitBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_usage_history")).isEqualTo(usageBefore);
        }

        @Test
        void 양수가_아닌_QR_생성_쿠폰_ID는_공통_입력_오류로_반환한다() throws Exception {
            Map<String, Object> before = coupon();
            mockMvc.perform(post("/v1/coupons/0:generateQr").with(user(MEMBER)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
            assertThat(coupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @Test
        void 인증_없이는_QR을_생성할_수_없다() throws Exception {
            mockMvc.perform(post(QR_PATH)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH-005"));
            assertThat(coupon().get("qr_version")).isEqualTo(3);
        }

        @Test
        void 회원_외_역할은_QR을_생성할_수_없다() throws Exception {
            mockMvc.perform(post(QR_PATH).with(user(new CustomUserDetails(17L, Role.OWNER))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @Test
        void 다른_회원의_쿠폰은_접근을_거절한다() throws Exception {
            mockMvc.perform(post(QR_PATH)
                            .with(user(new CustomUserDetails(18L, Role.MEMBER))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-011"));
        }

        @Test
        void 회원의_현재_DB_상태가_정지면_QR을_변경하지_않는다() throws Exception {
            jdbcTemplate.update("UPDATE member SET status = 'SUSPENDED', suspended_at = ? WHERE member_id = 17", NOW);
            Map<String, Object> before = coupon();
            mockMvc.perform(post(QR_PATH).with(user(MEMBER))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COUPON-003"));
            assertThat(coupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @Test
        void 이미지_생성이_실패하면_토큰과_버전_교체도_롤백한다() throws Exception {
            Map<String, Object> before = coupon();
            doThrow(new IllegalStateException("test image failure")).when(imageEncoder).encode(any(UUID.class));
            mockMvc.perform(post(QR_PATH).with(user(MEMBER))).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-001"));
            assertThat(coupon()).usingRecursiveComparison().isEqualTo(before);
        }

        @Test
        void 쿠폰_저장이_실패하면_QR_변경을_롤백한다() throws Exception {
            Map<String, Object> before = coupon();
            jdbcTemplate.execute("ALTER TABLE member_coupon ADD CONSTRAINT chk_test_qr_failure CHECK (qr_version <= 3)");
            try {
                mockMvc.perform(post(QR_PATH).with(user(MEMBER)))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("COMMON-001"));

                assertThat(coupon()).usingRecursiveComparison().isEqualTo(before);
            } finally {
                jdbcTemplate.execute("ALTER TABLE member_coupon DROP CHECK chk_test_qr_failure");
            }
        }

        @Test
        void 동시_QR_갱신은_직렬화하여_마지막_토큰만_저장한다() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Future<MvcResult>> second = new AtomicReference<>();
            MvcResult last;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    couponCommandService.generateQr(17L, 1001L);
                    second.set(executor.submit(() -> {
                        started.countDown();
                        return mockMvc.perform(post(QR_PATH).with(user(MEMBER))).andReturn();
                    }));
                    assertBlocked(started, second.get());
                });
                last = second.get().get(5, TimeUnit.SECONDS);
            }
            Map<String, Object> saved = coupon();
            ByteBuffer bytes = ByteBuffer.wrap((byte[]) saved.get("qr_token"));
            UUID token = new UUID(bytes.getLong(), bytes.getLong());
            assertThat(saved.get("qr_version")).isEqualTo(5);
            status().isOk().match(last);
            jsonPath("$.data.qrImageBytes").value(imageEncoder.encode(token)).match(last);
        }

        @Test
        void 삭제가_먼저_잠그면_대기하던_QR_갱신은_삭제_후_거절된다() throws Exception {
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Future<MvcResult>> renewal = new AtomicReference<>();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    couponCommandService.deleteCoupon(17L, 1001L);
                    renewal.set(executor.submit(() -> {
                        started.countDown();
                        return mockMvc.perform(post(QR_PATH).with(user(MEMBER))).andReturn();
                    }));
                    assertBlocked(started, renewal.get());
                });
                assertRenewalRejected(renewal.get());
            }
            assertThat(coupon().get("status")).isEqualTo("REMOVED");
            assertThat(coupon().get("qr_token")).isNull();
            assertThat(coupon().get("qr_expires_at")).isNull();
            assertThat(coupon().get("qr_version")).isEqualTo(4);
        }

        @Test
        void 잠금을_기다리는_중_사용_시간이_끝나면_잠금_획득_후_거절된다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET usable_end_at = ?", NOW.plusSeconds(30));
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Future<MvcResult>> renewal = new AtomicReference<>();
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    memberCouponRepository.findByIdAndMemberId(1001L, 17L).orElseThrow();
                    renewal.set(executor.submit(() -> {
                        started.countDown();
                        return mockMvc.perform(post(QR_PATH).with(user(MEMBER))).andReturn();
                    }));
                    assertBlocked(started, renewal.get());
                    currentTime.set(NOW.plusSeconds(30).atZone(ClockConfig.ZONE).toInstant());
                });
                assertRenewalRejected(renewal.get());
            }
            assertThat(coupon().get("qr_version")).isEqualTo(3);
        }

        // 별도 스레드의 요청이 시작되었으며 행 잠금 해제까지 대기하는지 검증한다.
        private void assertBlocked(CountDownLatch started, Future<?> pending) {
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        }

        // 잠금 획득 후 사용할 수 없게 된 쿠폰의 QR 갱신이 거절되는지 검증한다.
        private void assertRenewalRejected(Future<MvcResult> renewal) throws Exception {
            MvcResult response = renewal.get(5, TimeUnit.SECONDS);
            status().isConflict().match(response);
            jsonPath("$.code").value("COUPON-002").match(response);
        }

        // QR 갱신 전후의 토큰과 버전, 상태를 비교할 회원 쿠폰을 DB에서 조회한다.
        private Map<String, Object> coupon() {
            return jdbcTemplate.queryForMap("SELECT * FROM member_coupon WHERE member_coupon_id = 1001");
        }
    }

    @Nested
    @DisplayName("매장별 쿠폰 이벤트 상태 조회")
    @Transactional
    class EventStatusQuery {

        private static final String PATH = "/v1/coupon-events:getStatus";
        private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
        private static final LocalDateTime NOW = TODAY.atTime(12, 0);

        @BeforeEach
        void setUp() {
            when(clock.withZone(ClockConfig.ZONE)).thenReturn(
                    Clock.fixed(Instant.parse("2026-10-14T03:00:00.123456Z"), ClockConfig.ZONE));
            jdbcTemplate.update("""
                    INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at)
                    VALUES (17, 'coupon-event-17', '점심헌터', 'ACTIVE', ?, ?),
                           (18, 'coupon-event-18', '점심헌터', 'ACTIVE', ?, ?)
                    """, NOW, NOW, NOW, NOW);
            insertEvent(501, 10, TODAY, "ACTIVE");
            insertEvent(502, 20, TODAY, "SCHEDULED");
            insertEvent(503, 30, TODAY, "PAUSED");
            insertEvent(504, 40, TODAY, "ENDED");
            insertEvent(505, 50, TODAY.minusDays(1), "ACTIVE");
            insertEvent(506, 60, TODAY.plusDays(1), "ACTIVE");
            insertInventory(1, "AVAILABLE");
            insertInventory(2, "AVAILABLE");
            insertInventory(3, "ISSUED");
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                            qr_version, status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (1001, 501, 17, UNHEX(LPAD('1001', 32, '0')), 0, 'REMOVED', ?, ?, ?, ?),
                           (1002, 502, 18, UNHEX(LPAD('1002', 32, '0')), 0, 'ISSUED', ?, ?, ?, ?)
                    """, NOW, NOW.plusHours(3), NOW, NOW, NOW, NOW.plusHours(3), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                    VALUES (17, ?, 1, ?, ?)
                    """, TODAY, NOW, NOW);
        }

        @Test
        void 오늘의_조회_대상과_AVAILABLE_재고만_반환하고_삭제한_발급_이력을_유지한다() throws Exception {
            List<Long> storeIds = List.of(20L, 10L, 30L, 40L, 50L, 60L, 70L);
            when(campaignQueryService.findByStoreIdIn(storeIds)).thenReturn(List.of(
                    campaign(10L, CampaignStatus.ACTIVE), campaign(20L, CampaignStatus.SCHEDULED),
                    campaign(30L, CampaignStatus.PAUSED)));
            var eventsBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_event ORDER BY coupon_event_id");
            var inventoryBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY coupon_inventory_id");
            var couponsBefore = jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id");
            var limitsBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id");

            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeIds\":[20,10,30,40,50,60,70]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.serverTime").value("2026-10-14T12:00:00.123+09:00"))
                    .andExpect(jsonPath("$.data.items.length()").value(7))
                    .andExpect(jsonPath("$.data.items[0].storeId").value(20))
                    .andExpect(jsonPath("$.data.items[1].storeId").value(10))
                    .andExpect(jsonPath("$.data.items[2].storeId").value(30))
                    .andExpect(jsonPath("$.data.items[3].storeId").value(40))
                    .andExpect(jsonPath("$.data.items[4].storeId").value(50))
                    .andExpect(jsonPath("$.data.items[5].storeId").value(60))
                    .andExpect(jsonPath("$.data.items[6].storeId").value(70))
                    .andExpect(jsonPath("$.data.items[0].eventState").value("SCHEDULED"))
                    .andExpect(jsonPath("$.data.items[0].remainingQuantity").value(0))
                    .andExpect(jsonPath("$.data.items[0].hasIssuedToday").value(false))
                    .andExpect(jsonPath("$.data.items[1].hasCampaign").value(true))
                    .andExpect(jsonPath("$.data.items[1].eventId").value(501))
                    .andExpect(jsonPath("$.data.items[1].eventState").value("OPEN"))
                    .andExpect(jsonPath("$.data.items[1].remainingQuantity").value(2))
                    .andExpect(jsonPath("$.data.items[1].usableStartTime").value("2026-10-14T12:30:00+09:00"))
                    .andExpect(jsonPath("$.data.items[1].hasIssuedToday").value(true))
                    .andExpect(jsonPath("$.data.items[1].cannotIssueReason").value("ALREADY_ISSUED"))
                    .andExpect(jsonPath("$.data.items[2].eventState").value("NO_EVENT"))
                    .andExpect(jsonPath("$.data.items[3].eventState").value("NO_EVENT"))
                    .andExpect(jsonPath("$.data.items[4].eventState").value("NO_EVENT"))
                    .andExpect(jsonPath("$.data.items[5].eventState").value("NO_EVENT"))
                    .andExpect(jsonPath("$.data.items[6].eventState").value("NO_EVENT"))
                    .andExpect(jsonPath("$.data.items[6].eventId").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.items[6].usableStartTime").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.items[6].usableEndTime").value(org.hamcrest.Matchers.nullValue()));

            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_event ORDER BY coupon_event_id")).isEqualTo(eventsBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY coupon_inventory_id"))
                    .usingRecursiveComparison().isEqualTo(inventoryBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM member_coupon ORDER BY member_coupon_id"))
                    .usingRecursiveComparison().isEqualTo(couponsBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit ORDER BY coupon_daily_limit_id")).isEqualTo(limitsBefore);
        }

        @Test
        void 찜_contract를_연결하기_전에는_발급_가능으로_안내하지_않는다() throws Exception {
            mockMvc.perform(post(PATH).with(user(new CustomUserDetails(18L, Role.MEMBER)))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"storeIds\":[10]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].wished").value(false))
                    .andExpect(jsonPath("$.data.items[0].canIssue").value(false))
                    .andExpect(jsonPath("$.data.items[0].cannotIssueReason").value("NOT_WISHED"));
        }

        @Test
        void 발급_시간_안에_AVAILABLE_재고가_없으면_품절로_표시한다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_inventory SET status = 'ISSUED' WHERE coupon_event_id = 501");

            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeIds\":[10]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].eventState").value("SOLD_OUT"))
                    .andExpect(jsonPath("$.data.items[0].remainingQuantity").value(0));
        }

        @Test
        void 같은_매장의_조회_대상_이벤트가_두_개면_서버_오류로_거절한다() throws Exception {
            jdbcTemplate.update("UPDATE coupon_event SET store_id = 10 WHERE coupon_event_id = 502");

            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeIds\":[10]}"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"storeIds\":null}", "{\"storeIds\":[]}", "{\"storeIds\":[null]}",
                "{\"storeIds\":[0]}", "{\"storeIds\":[-1]}", "{\"storeIds\":[10,10]}"})
        void 잘못된_목록은_공통_입력_오류로_거절한다(String body) throws Exception {
            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 매장_100개를_초과하면_거절한다() throws Exception {
            String ids = LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(","));

            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeIds\":[" + ids + "]}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 매장_100개는_입력_순서대로_반환한다() throws Exception {
            String ids = LongStream.rangeClosed(1, 100).mapToObj(Long::toString).collect(Collectors.joining(","));

            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeIds\":[" + ids + "]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(100))
                    .andExpect(jsonPath("$.data.items[99].storeId").value(100));
        }

        @ParameterizedTest
        @ValueSource(strings = {"{", "null", "{\"storeIds\":{}}", "{\"storeIds\":[\"invalid\"]}",
                "{\"storeIds\":[10.9]}", "{\"storeIds\":[\"10\"]}"})
        void 읽을_수_없는_본문은_공통_해석_오류로_거절한다(String body) throws Exception {
            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        }

        @Test
        void 인증이_없으면_거절한다() throws Exception {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"storeIds\":[10]}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
        }

        @ParameterizedTest
        @EnumSource(value = Role.class, names = "MEMBER", mode = EnumSource.Mode.EXCLUDE)
        void MEMBER_외_역할은_거절한다(Role role) throws Exception {
            mockMvc.perform(post(PATH).with(user(new CustomUserDetails(17L, role)))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"storeIds\":[10]}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @Test
        void Swagger가_POST_요청_제약과_서버_시각과_null_필드를_설명한다() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/v1/coupon-events:getStatus'].post.requestBody.content['application/json'].example.storeIds")
                            .value(org.hamcrest.Matchers.contains(10, 20, 30)))
                    .andExpect(jsonPath("$.paths['/v1/coupon-events:getStatus'].post.responses['200'].content['*/*'].example.data.serverTime")
                            .value("2026-10-14T12:00:00.123+09:00"))
                    .andExpect(jsonPath("$.paths['/v1/coupon-events:getStatus'].post.responses['200'].content['*/*'].example.data.items[2].eventId")
                            .value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.components.schemas.CouponEventStatusRequest.properties.storeIds.minItems").value(1))
                    .andExpect(jsonPath("$.components.schemas.CouponEventStatusRequest.properties.storeIds.maxItems").value(100))
                    .andExpect(jsonPath("$.components.schemas.CouponEventStatusRequest.properties.storeIds.uniqueItems").value(true));
        }

        private void insertEvent(long eventId, long storeId, LocalDate businessDate, String state) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (?, ?, ?, 100, ?, ?, ?, ?, '런치식당', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, eventId, eventId, businessDate, businessDate.atTime(12, 30), businessDate.atTime(15, 0),
                    state, storeId, NOW, NOW);
        }

        private void insertInventory(int sequence, String state) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                            usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (501, ?, UNHEX(LPAD(?, 32, '0')), ?, ?, ?, ?, ?)
                    """, sequence, String.valueOf(sequence), state, NOW, NOW.plusHours(3), NOW, NOW);
        }

        private CampaignInfo campaign(Long storeId, CampaignStatus status) {
            return new CampaignInfo(storeId, 1L, storeId, status, 100, null, null, null, null,
                    null, null, 0, null, 0, null, List.of(), null, null, null, null, null);
        }
    }

    @Nested
    @DisplayName("QR 사용")
    class Redeem {

        private static final String PATH = "/v1/owner/coupon-usages";
        private static final UUID TOKEN = UUID.fromString("6c75d5bc-cae3-4eeb-a573-fc3bcb1673a5");
        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0, 0, 123456789);
        private static final CustomUserDetails OWNER = new CustomUserDetails(9L, Role.OWNER);

        @Autowired ObjectMapper objectMapper;
        @LocalServerPort int port;
        @Value("${jwt.secret}") String jwtSecret;
        private final AtomicReference<Instant> currentTime = new AtomicReference<>();

        @BeforeEach
        void setUp() {
            currentTime.set(NOW.atZone(ClockConfig.ZONE).toInstant());
            when(clock.withZone(ClockConfig.ZONE)).thenAnswer(invocation -> Clock.fixed(currentTime.get(), ClockConfig.ZONE));
            when(clock.instant()).thenAnswer(invocation -> currentTime.get());
            when(ownerStatusQuery.findCurrentStatus(9L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            when(stores.canManageStore(9L, 10L)).thenReturn(true);
            when(stores.findAllById(List.of(10L))).thenReturn(List.of(store(37.5)));
            when(ownerTokenPolicy.supports(Role.OWNER)).thenReturn(true);
            when(ownerTokenPolicy.isValid(any(), anyLong(), any(), any(), any())).thenReturn(true);
            jdbcTemplate.update("""
                    INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at)
                    VALUES (17, 'coupon-use-test', '점심헌터', 'ACTIVE', ?, ?)
                    """, NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                        usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                        discount_type, discount_value, created_at, updated_at)
                    VALUES (81, 81, ?, 100, ?, ?, 'ACTIVE', 10, '런치식당', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, NOW.toLocalDate(), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                        qr_version, qr_token, qr_expires_at, status, usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (1001, 81, 17, UUID_TO_BIN(UUID()), 3, UUID_TO_BIN(?), ?, 'ISSUED', ?, ?, ?, ?)
                    """, TOKEN.toString(), NOW.plusSeconds(30), NOW.minusHours(1), NOW.plusHours(1), NOW, NOW);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history");
            jdbcTemplate.update("DELETE FROM member_coupon");
            jdbcTemplate.update("DELETE FROM coupon_event");
            jdbcTemplate.update("DELETE FROM member WHERE member_id=17");
        }

        @ParameterizedTest
        @CsvSource({"ALL,AMOUNT,3000,3000", "ALL,AMOUNT,3005,3010",
                "MENU,AMOUNT,9000,4500", "MENU,PERCENT,15,680"})
        void 실제_HTTP로_가격_없이_세_할인_조합과_JPA_저장_결과를_확인한다(
                String target, String type, int value, int expected) throws Exception {
            if (target.equals("MENU")) {
                jdbcTemplate.update("""
                        UPDATE coupon_event SET discount_type=?, discount_value=?, discount_target_type='MENU', target_menu_id=701,
                            target_menu_name='테스트 메뉴', target_menu_price=4500, target_menu_discounted_price=?
                        WHERE coupon_event_id=81
                        """, type, value, type.equals("PERCENT") ? 3820 : 0);
            } else {
                jdbcTemplate.update("UPDATE coupon_event SET discount_value=? WHERE coupon_event_id=81", value);
            }
            try (HttpClient client = HttpClient.newHttpClient()) {
                var response = client.send(httpRequest(body(), accessToken(Role.OWNER)), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(201);
                var data = objectMapper.readTree(response.body()).path("data");
                assertThat(data.path("memberCouponId").asLong()).isEqualTo(1001);
                assertThat(data.path("usageHistoryId").asLong()).isPositive();
                assertThat(data.path("discountAmount").asInt()).isEqualTo(expected);
                assertThat(data.path("useTime").asText()).isEqualTo("2026-10-10T12:00:00.123457+09:00");
            }
            assertThat(state()).isEqualTo("USED");
            assertThat(historyCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT discount_amount FROM coupon_usage_history", Integer.class)).isEqualTo(expected);
            assertThat(jdbcTemplate.queryForObject("SELECT created_at FROM coupon_usage_history", LocalDateTime.class))
                    .isEqualTo(NOW.withNano(123457000));
            assertThat(jdbcTemplate.queryForObject("SELECT updated_at FROM coupon_usage_history", LocalDateTime.class))
                    .isEqualTo(NOW.withNano(123457000));
        }

        @Test
        void 실제_HTTP는_쿠키가_없거나_점주가_아니면_거절한다() throws Exception {
            try (HttpClient client = HttpClient.newHttpClient()) {
                assertThat(client.send(httpRequest(body(), null), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
                assertThat(client.send(httpRequest(body(), accessToken(Role.MEMBER)), HttpResponse.BodyHandlers.ofString()).statusCode())
                        .isEqualTo(403);
            }
            assertUnchanged();
        }

        @ParameterizedTest
        @ValueSource(strings = {"longitude", "latitude", "qrToken"})
        void 위치와_QR_토큰의_필수값을_검사한다(String field) throws Exception {
            var request = objectMapper.readTree(body()).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) request).remove(field);
            mockMvc.perform(post(PATH).with(user(OWNER)).contentType(MediaType.APPLICATION_JSON)
                            .content(request.toString()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
            assertUnchanged();
        }

        @Test
        void 전체_메뉴_정률_스냅샷은_DB가_거절한다() {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE coupon_event SET discount_type='PERCENT', discount_value=15 WHERE coupon_event_id=81"))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_coupon_event_discount_combination");
            assertUnchanged();
        }

        @ParameterizedTest
        @CsvSource({"inactive,COUPON-013", "permission,COUPON-011", "location,COUPON-009"})
        void 점주_상태와_매장_권한과_위치를_검사한다(String condition, String code) throws Exception {
            switch (condition) {
                case "inactive" -> when(ownerStatusQuery.findCurrentStatus(9L)).thenReturn(Optional.of(OwnerAccountStatus.SUSPENDED));
                case "permission" -> when(stores.canManageStore(9L, 10L)).thenReturn(false);
                case "location" -> when(stores.findAllById(List.of(10L))).thenReturn(List.of(store(38.0)));
            }
            assertResponse(use(), 403, code);
            assertUnchanged();
        }

        @Test
        void 만료_QR은_거절하고_사용한_QR의_재요청은_만료_후에도_구분한다() throws Exception {
            jdbcTemplate.update("UPDATE member_coupon SET qr_expires_at=? WHERE member_coupon_id=1001", NOW.truncatedTo(ChronoUnit.MICROS));
            assertResponse(use(), 409, "COUPON-010");
            jdbcTemplate.update("UPDATE member_coupon SET qr_expires_at=? WHERE member_coupon_id=1001", NOW.plusSeconds(30));
            assertResponse(use(), 201, "SUCCESS");
            currentTime.set(NOW.plusDays(1).atZone(ClockConfig.ZONE).toInstant());
            assertResponse(use(), 409, "COUPON-012");
            assertThat(historyCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT BIN_TO_UUID(qr_token) FROM member_coupon", String.class)).isEqualTo(TOKEN.toString());
        }

        @Test
        void 같은_QR의_실제_HTTP_동시_요청은_한_건만_성공한다() throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            HttpRequest request = httpRequest(body(), accessToken(Role.OWNER));
            try (HttpClient client = HttpClient.newHttpClient(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<HttpResponse<String>>> futures = IntStream.range(0, 3).mapToObj(index -> executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return client.send(request, HttpResponse.BodyHandlers.ofString());
                })).toList();
                start.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (var future : futures) {
                    var response = future.get(20, TimeUnit.SECONDS);
                    statuses.add(response.statusCode());
                    if (response.statusCode() == 409) {
                        assertThat(objectMapper.readTree(response.body()).path("code").asText()).isEqualTo("COUPON-012");
                    }
                }
                assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409);
            }
            assertThat(historyCount()).isEqualTo(1);
            assertThat(funnel.received).hasSize(1).allMatch(event -> event instanceof CouponRedeemedEvent);
        }

        @ParameterizedTest
        @ValueSource(strings = {"coupon", "history"})
        void 쿠폰과_이력의_저장_실패는_함께_롤백한다(String target) throws Exception {
            String table = target.equals("coupon") ? "member_coupon" : "coupon_usage_history";
            String condition = target.equals("coupon") ? "status<>'USED'" : "discount_amount=0";
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT chk_usage_failure CHECK (" + condition + ")");
            try {
                assertResponse(use(), 500, "COMMON-001");
                assertUnchanged();
                assertThat(funnel.received).isEmpty();
            } finally {
                jdbcTemplate.execute("ALTER TABLE " + table + " DROP CHECK chk_usage_failure");
            }
        }

        @Test
        void HTTP_사용을_감싼_트랜잭션이_롤백하면_분석_리스너와_사용_결과에_남지_않는다() {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                assertDoesNotThrow(() -> assertThat(use().getResponse().getStatus()).isEqualTo(201));
                assertThat(funnel.received).isEmpty();
                transaction.setRollbackOnly();
            });
            assertThat(funnel.received).isEmpty();
            assertUnchanged();
        }

        @Test
        void 새_QR을_사용한_뒤_잠금을_얻은_이전_QR은_유효하지_않다() throws Exception {
            CountDownLatch read = new CountDownLatch(1);
            var real = mockingDetails(memberCouponRepository).getMockCreationSettings().getDefaultAnswer();
            doAnswer(invocation -> {
                Object result = real.answer(invocation);
                read.countDown();
                return result;
            }).when(memberCouponRepository).findQrReference(TOKEN);
            AtomicReference<Future<MvcResult>> pending = new AtomicReference<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    jdbcTemplate.queryForObject("SELECT member_coupon_id FROM member_coupon WHERE member_coupon_id=1001 FOR UPDATE", Long.class);
                    pending.set(executor.submit(this::use));
                    assertDoesNotThrow(() -> assertThat(read.await(5, TimeUnit.SECONDS)).isTrue());
                    assertThatThrownBy(() -> pending.get().get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    assertDoesNotThrow(() -> {
                        mockMvc.perform(post("/v1/coupons/1001:generateQr").with(user(MEMBER))).andExpect(status().isOk());
                        UUID renewed = memberCouponRepository.findById(1001L).orElseThrow().getQrToken();
                        currentTime.set(NOW.plusSeconds(1).atZone(ClockConfig.ZONE).toInstant());
                        mockMvc.perform(post(PATH).with(user(OWNER)).contentType(MediaType.APPLICATION_JSON)
                                .content(body().replace(TOKEN.toString(), renewed.toString()))).andExpect(status().isCreated());
                    });
                });
                assertResponse(pending.get().get(10, TimeUnit.SECONDS), 409, "COUPON-010");
            }
            assertThat(historyCount()).isEqualTo(1);
        }

        private HttpRequest httpRequest(String body, String token) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (token != null) {
                request.header("Cookie", "accessToken=" + token);
            }
            return request.build();
        }

        private String accessToken(Role role) {
            Instant now = Instant.now();
            return Jwts.builder().subject(role == Role.MEMBER ? "17" : "9").claim("role", role.name())
                    .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
                    .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).compact();
        }

        private StoreInfo store(double latitude) {
            return new StoreInfo(10L, "런치식당", "https://example.test/store.png", latitude, 127.0);
        }

        private String body() {
            return "{\"longitude\":127.0,\"latitude\":37.5,\"qrToken\":\"" + TOKEN + "\"}";
        }

        private MvcResult use() throws Exception {
            return mockMvc.perform(post(PATH).with(user(OWNER)).contentType(MediaType.APPLICATION_JSON).content(body())).andReturn();
        }

        private void assertResponse(MvcResult result, int status, String code) throws Exception {
            assertThat(result.getResponse().getStatus()).isEqualTo(status);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText()).isEqualTo(code);
        }

        private String state() {
            return jdbcTemplate.queryForObject("SELECT status FROM member_coupon WHERE member_coupon_id=1001", String.class);
        }

        private int historyCount() {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_usage_history", Integer.class);
        }

        private void assertUnchanged() {
            assertThat(state()).isEqualTo("ISSUED");
            assertThat(historyCount()).isZero();
        }
    }
    static class FunnelRecorder {
        final List<Object> received = new java.util.concurrent.CopyOnWriteArrayList<>();

        @TransactionalEventListener(classes = {CouponIssueOpenedEvent.class,
                CouponIssuedEvent.class, CouponRedeemedEvent.class, CouponExpiredEvent.class})
        public void receiveAfterCommit(Object event) {
            received.add(event);
        }
    }
}
