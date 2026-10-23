package com.launchcatch.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.adserving.contract.CandidateStateUpdater;
import com.launchcatch.coupon.job.CouponPreparationJob;
import com.launchcatch.coupon.job.CouponIssueOpenJob;
import com.launchcatch.coupon.job.CouponIssueCloseJob;
import com.launchcatch.coupon.job.CouponExpirationJob;
import com.launchcatch.coupon.job.CouponConsistencyJob;
import com.launchcatch.coupon.CouponBatchTask;
import com.launchcatch.coupon.repository.CouponBatchRepository;
import com.launchcatch.coupon.service.CouponPreparationService;
import com.launchcatch.ops.service.BatchExecutionService;
import com.launchcatch.ops.scheduler.RetryBackoff;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreMenuQueryResult;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import java.time.LocalTime;
import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.contract.AccessTokenValidationPolicy;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignEndedEvent;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import com.launchcatch.coupon.contract.CouponStatsQueryService;
import com.launchcatch.coupon.contract.DailyCampaignCouponStats;
import com.launchcatch.coupon.contract.DailyMemberCouponStats;
import com.launchcatch.coupon.contract.MemberCouponStats;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.dto.CouponUsageHistoryResponse;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.CouponInventory;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponBatchIssueRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponInventoryRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.coupon.service.CouponBatchIssuanceService;
import com.launchcatch.coupon.service.CouponCommandService;
import com.launchcatch.coupon.model.CouponIssuanceOutcome;
import com.launchcatch.coupon.model.CouponIssuanceRequest;
import com.launchcatch.coupon.service.CouponLookUpService;
import com.launchcatch.coupon.contract.CouponIssueOpenedEvent;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import com.launchcatch.coupon.contract.CouponRedeemedEvent;
import com.launchcatch.coupon.contract.CouponExpiredEvent;
import com.launchcatch.coupon.service.CouponSequenceRecoveryService;
import com.launchcatch.coupon.service.OwnerCouponService;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "coupon.daily-limit=3",
        // 고정된 과거 영업일의 테스트 데이터가 실제 Redis 시계에서 즉시 만료되지 않게 한다.
        "coupon.sequence.retention-grace=36500d",
        "coupon.issue.batch.workers=2",
        "coupon.issue.batch.max-size=8",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.launchcatch.coupon.CouponIntegrationTest$SqlCapture"
}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(CouponIntegrationTest.FunnelRecorder.class)
public class CouponIntegrationTest extends IntegrationTestSupport {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final CustomUserDetails MEMBER = new CustomUserDetails(17L, Role.MEMBER);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired CouponLookUpService couponLookUpService;
    @Autowired CouponCommandService couponCommandService;
    @Autowired OwnerCouponService ownerCouponService;
    @Autowired com.launchcatch.ops.service.BatchJobRunner batchJobRunner;
    @Autowired CouponBatchIssuanceService batchIssuanceService;
    @MockitoSpyBean com.launchcatch.coupon.repository.CouponSequenceRepository sequences;
    @Autowired com.launchcatch.coupon.service.CouponSequenceService sequenceService;
    @Autowired CouponSequenceRecoveryService sequenceRecoveryService;
    @Autowired StringRedisTemplate redis;
    @Autowired CouponStatsQueryService couponStats;
    @Autowired FunnelRecorder funnel;
    @Autowired ObjectMapper objectMapper;
    @LocalServerPort int port;
    @Value("${jwt.secret}") String jwtSecret;

    @MockitoSpyBean Clock clock;
    @MockitoBean OwnerStatusQuery ownerStatusQuery;
    @MockitoBean CampaignQueryService campaignQueryService;
    @MockitoBean StoreQueryService stores;
    @MockitoSpyBean WishlistQueryService wishes;
    @MockitoBean CandidateStateUpdater candidates;
    @MockitoBean com.launchcatch.coupon.service.CouponSoldOutNotificationService soldOutNotifications;
    @MockitoBean(name = "ownerAccessTokenPolicy") AccessTokenValidationPolicy ownerTokenPolicy;
    @MockitoSpyBean CouponQrImageEncoder imageEncoder;
    @MockitoSpyBean MemberQueryService members;
    @MockitoSpyBean CouponEventRepository events;
    @MockitoSpyBean CouponInventoryRepository inventories;
    @MockitoSpyBean MemberCouponRepository memberCouponRepository;
    @MockitoSpyBean CouponBatchIssueRepository batchIssues;
    @MockitoSpyBean CouponUsageHistoryRepository histories;
    @MockitoSpyBean CouponBatchRepository batches;
    @MockitoBean RetryBackoff retryBackoff;

    @BeforeEach
    void setUpClock() {
        funnel.received.clear();
        doReturn(List.of()).when(wishes).findWishedCampaignIds(any());
        doReturn(false).when(wishes).isWished(any(), any());
        doReturn(Set.of()).when(wishes).findWishedMemberIds(any(), any());
        when(clock.withZone(ClockConfig.ZONE))
                .thenReturn(Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ClockConfig.ZONE));
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-10T03:00:00Z"));
    }

    // MockMvc는 실제 서버와 달리 비동기 응답의 재디스패치를 테스트가 직접 수행해야 한다.
    private ResultActions performIssue(MockHttpServletRequestBuilder requestBuilder) throws Exception {
        var pending = mockMvc.perform(requestBuilder)
                .andExpect(request().asyncStarted())
                .andReturn();
        pending.getAsyncResult(15_000L);
        return mockMvc.perform(asyncDispatch(pending));
    }

    private void stubActiveMembers() {
        doAnswer(invocation -> invocation.<Set<Long>>getArgument(0).stream()
                .collect(Collectors.toMap(id -> id, id -> MemberStatus.ACTIVE)))
                .when(members).findCurrentStatuses(any());
    }

    private void stubWishedMembers() {
        doAnswer(invocation -> Set.copyOf(invocation.<Set<Long>>getArgument(1)))
                .when(wishes).findWishedMemberIds(any(), any());
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
        void 회원별_일일_집계는_0원과_같은_할인액을_각각_더하고_회원_ID_순으로_반환한다() {
            event(81, 91, TODAY.minusDays(1), "ENDED");
            event(82, 92, TODAY, "ACTIVE");
            event(83, 93, TODAY.minusDays(1), "ENDED");
            coupon(101, 81, 18, "USED");
            coupon(102, 81, 17, "USED");
            coupon(103, 82, 17, "USED");
            coupon(104, 83, 17, "USED");
            coupon(105, 82, 19, "ISSUED");
            coupon(106, 82, 20, "USED");
            usage(101, TODAY.atTime(12, 0), 5000);
            usage(102, TODAY.atTime(12, 0), 0);
            usage(103, TODAY.atTime(12, 1), 1500);
            usage(104, TODAY.atTime(12, 2), 1500);
            usage(106, TODAY.atTime(12, 3), 0);

            assertThat(couponStats.dailyMemberStats(TODAY)).containsExactly(
                    new DailyMemberCouponStats(17, 3, 3000), new DailyMemberCouponStats(18, 1, 5000),
                    new DailyMemberCouponStats(20, 1, 0));
        }

        @Test
        void 회원별_일일_집계는_발급일과_별개로_KST_사용일의_자정을_포함하고_다음_자정은_제외한다() {
            event(81, 91, TODAY.minusDays(3), "ENDED");
            event(82, 92, TODAY.minusDays(2), "ENDED");
            event(83, 93, TODAY.minusDays(1), "ENDED");
            event(84, 94, TODAY, "ACTIVE");
            coupon(101, 81, 17, "USED");
            coupon(102, 82, 17, "USED");
            coupon(103, 83, 17, "USED");
            coupon(104, 84, 17, "USED");
            usage(101, TODAY.atStartOfDay().minusNanos(1000), 100);
            usage(102, TODAY.atStartOfDay(), 200);
            usage(103, TODAY.plusDays(1).atStartOfDay().minusNanos(1000), 300);
            usage(104, TODAY.plusDays(1).atStartOfDay(), 400);

            assertThat(couponStats.dailyMemberStats(TODAY))
                    .containsExactly(new DailyMemberCouponStats(17, 2, 500));
        }

        @Test
        void 회원별_일일_할인액의_합계가_32비트_범위를_넘어도_잘리지_않는다() {
            event(81, 91, TODAY.minusDays(1), "ENDED");
            event(82, 92, TODAY, "ACTIVE");
            coupon(101, 81, 17, "USED");
            coupon(102, 82, 17, "USED");
            usage(101, TODAY.atTime(12, 0), 1_500_000_000);
            usage(102, TODAY.atTime(12, 1), 1_500_000_000);

            assertThat(couponStats.dailyMemberStats(TODAY))
                    .containsExactly(new DailyMemberCouponStats(17, 2, 3_000_000_000L));
        }

        @Test
        void 다른_날의_사용과_발급만_있는_회원은_일일_집계에_포함하지_않는다() {
            event(81, 91, TODAY.minusDays(1), "ENDED");
            event(82, 92, TODAY, "ACTIVE");
            coupon(101, 81, 17, "USED");
            coupon(102, 82, 18, "ISSUED");
            usage(101, TODAY.minusDays(1).atTime(12, 0), 1500);

            assertThat(couponStats.dailyMemberStats(TODAY)).isEmpty();
        }

        @Test
        void 회원별_모든_날짜의_사용_수와_할인액을_더하면_누적_집계와_같다() {
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

            var first = couponStats.dailyMemberStats(TODAY.minusDays(2)).getFirst();
            var second = couponStats.dailyMemberStats(TODAY.minusDays(1)).getFirst();
            var third = couponStats.dailyMemberStats(TODAY).getFirst();

            assertThat(couponStats.memberStats(17)).isEqualTo(new MemberCouponStats(
                    first.usedCount() + second.usedCount() + third.usedCount(),
                    first.discountAmount() + second.discountAmount() + third.discountAmount()));
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
    @DisplayName("발급 SQL 개수")
    class IssuanceSql {
        @BeforeEach
        void setUp() {
            doCallRealMethod().when(wishes).findWishedMemberIds(99091L, Set.of(99017L, 99018L));
            jdbcTemplate.update("""
                    INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at)
                    VALUES (99017, 'issuance-sql-test', '회원', 'ACTIVE', ?, ?),
                           (99018, 'issuance-sql-test-2', '회원2', 'ACTIVE', ?, ?)
                    """, TODAY.atTime(12, 0), TODAY.atTime(12, 0), TODAY.atTime(12, 0), TODAY.atTime(12, 0));
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                        usable_start_at, usable_end_at, status, store_id, store_name,
                        discount_target_type, discount_type, discount_value, created_at, updated_at)
                    VALUES (99081, 99091, ?, 2, ?, ?, 'ACTIVE', 99010, '매장',
                        'ALL', 'AMOUNT', 1000, ?, ?)
                    """, TODAY, TODAY.atTime(11, 0), TODAY.atTime(14, 0), TODAY.atTime(12, 0), TODAY.atTime(12, 0));
            for (int sequence = 1; sequence <= 2; sequence++) {
                jdbcTemplate.update("""
                        INSERT INTO coupon_inventory (coupon_event_id, sequence_no, coupon_code, status,
                            usable_start_at, usable_end_at, created_at, updated_at)
                        VALUES (99081, ?, UNHEX(MD5(?)), 'AVAILABLE', ?, ?, ?, ?)
                        """, sequence, "issuance-sql-" + sequence, TODAY.atTime(11, 0), TODAY.atTime(14, 0),
                        TODAY.atTime(12, 0), TODAY.atTime(12, 0));
            }
            jdbcTemplate.update("""
                    INSERT INTO wishlist (member_id, campaign_id, store_id, created_at, updated_at)
                    VALUES (99017, 99091, 99010, ?, ?), (99018, 99091, 99010, ?, ?)
                    """, TODAY.atTime(12, 0), TODAY.atTime(12, 0), TODAY.atTime(12, 0), TODAY.atTime(12, 0));
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM event_log WHERE member_id IN (99017,99018)");
            jdbcTemplate.update("DELETE FROM member_coupon WHERE coupon_event_id=99081");
            jdbcTemplate.update("DELETE FROM coupon_daily_limit WHERE member_id IN (99017,99018)");
            jdbcTemplate.update("DELETE FROM coupon_inventory WHERE coupon_event_id=99081");
            jdbcTemplate.update("DELETE FROM coupon_event WHERE coupon_event_id=99081");
            jdbcTemplate.update("DELETE FROM wishlist WHERE member_id IN (99017,99018)");
            jdbcTemplate.update("DELETE FROM member WHERE member_id IN (99017,99018)");
        }

        @ParameterizedTest
        @CsvSource({"false, false", "true, false", "false, true", "true, true"})
        void 프로필과_기존_카운터_유무에_관계없이_두_회원의_발급은_묶음_하나에_SQL_열_개로_처리한다(
                boolean hasProfile, boolean hasCounter) {
            if (hasProfile) {
                jdbcTemplate.update("""
                        INSERT INTO member_profile (member_id, gender, age_group, created_at, updated_at)
                        VALUES (99017, 'MALE', 'AGE_20S', ?, ?), (99018, 'MALE', 'AGE_20S', ?, ?)
                        """, TODAY.atTime(12, 0), TODAY.atTime(12, 0), TODAY.atTime(12, 0), TODAY.atTime(12, 0));
            }
            if (hasCounter) {
                jdbcTemplate.update("""
                        INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                        VALUES (99017, ?, 0, ?, ?), (99018, ?, 0, ?, ?)
                        """, TODAY, TODAY.atTime(12, 0), TODAY.atTime(12, 0),
                        TODAY, TODAY.atTime(12, 0), TODAY.atTime(12, 0));
            }

            sequences.rebuild(99081L, List.of(1, 2), 0, Instant.now().plusSeconds(3600).toEpochMilli());
            sequenceService.resume(99081L);
            CouponIssuanceRequest request = new CouponIssuanceRequest(99017L, 99081L, TODAY.atTime(12, 0));
            CouponIssuanceRequest second = new CouponIssuanceRequest(99018L, 99081L, TODAY.atTime(12, 0));
            List<String> statements = SqlCapture.capture(() -> batchIssuanceService.issueBatch(List.of(request, second)));

            /*
             * Hibernate 검사기는 이벤트 잠금, 회원 상태, 찜 조회 세 문장만 센다.
             * 카운터 생성과 잠금, 발급 기록, 재고 갱신, 쿠폰 코드, 카운터 증가, 쿠폰 저장 일곱 문장은 JdbcTemplate이 보낸다.
             */
            assertThat(statements).hasSize(3);
            assertThat(statements).noneMatch(sql -> sql.contains("member_profile"));
            assertThat(statements).noneMatch(sql -> sql.startsWith("select") && sql.contains("coupon_daily_limit"));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT issued_count FROM coupon_daily_limit WHERE member_id=99017", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM member_coupon WHERE coupon_event_id=99081", Integer.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM coupon_inventory WHERE coupon_event_id=99081 AND status='ISSUED'
                    """, Integer.class)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM member_coupon coupon
                      JOIN coupon_inventory inventory ON inventory.coupon_code = coupon.coupon_code
                     WHERE coupon.coupon_event_id=99081 AND coupon.member_id=99017
                       AND coupon.status='ISSUED' AND inventory.status='ISSUED'
                       AND coupon.usable_start_at=? AND coupon.usable_end_at=?
                    """, Integer.class, TODAY.atTime(11, 0), TODAY.atTime(14, 0))).isEqualTo(1);
        }

    }

    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> STATEMENTS = new ThreadLocal<>();
        private static final ThreadLocal<Runnable> BEFORE_COUPON_LOCK = new ThreadLocal<>();

        @Override
        public String inspect(String sql) {
            Runnable beforeLock = BEFORE_COUPON_LOCK.get();
            if (beforeLock != null && sql.contains("member_coupon") && sql.toLowerCase(Locale.ROOT).contains("for update")) {
                beforeLock.run();
            }
            List<String> statements = STATEMENTS.get();
            if (statements != null) {
                statements.add(sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT));
            }
            return sql;
        }

        static void observeCouponLocks(Runnable beforeLock, Runnable action) {
            BEFORE_COUPON_LOCK.set(beforeLock);
            try {
                action.run();
            } finally {
                BEFORE_COUPON_LOCK.remove();
            }
        }

        static List<String> capture(Runnable action) {
            STATEMENTS.set(new ArrayList<>());
            try {
                action.run();
                return List.copyOf(STATEMENTS.get());
            } finally {
                STATEMENTS.remove();
            }
        }
    }

    @Nested
    @DisplayName("캠페인 이벤트의 쿠폰 회차 반영")
    class CampaignEventState {

        private static final LocalDateTime CHANGED_AT = TODAY.atTime(11, 30);

        @BeforeEach
        void setUp() {
            event(81, 91, TODAY, "ACTIVE");
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
        void 캠페인_트랜잭션_안에서_회차_상태를_함께_바꾼다() {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                publisher.publishEvent(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));
                assertThat(states()).containsExactly("PAUSED");
                assertThat(changedAt()).isEqualTo(CHANGED_AT);
            });

            assertThat(states()).containsExactly("PAUSED");
            assertThat(changedAt()).isEqualTo(CHANGED_AT);
        }

        @Test
        void 중단을_받으면_해당_영업일의_회차만_멈추고_발급을_거절한다() throws Exception {
            event(82, 91, TODAY.minusDays(1), "ACTIVE");
            event(83, 91, TODAY.plusDays(1), "SCHEDULED");
            event(84, 92, TODAY, "ACTIVE");

            publish(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));

            assertThat(states()).containsExactly("PAUSED", "ACTIVE", "SCHEDULED", "ACTIVE");
            performIssue(post("/v1/coupons").with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventId\":81}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON-002"));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_coupon", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_daily_limit", Integer.class)).isZero();
        }

        @Test
        void 재개는_해당_영업일의_종료되지_않은_회차만_ACTIVE로_바꾼다() {
            event(82, 91, TODAY.plusDays(1), "PAUSED");
            publish(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));

            publish(new CampaignStatusChangedEvent(91, TODAY, true, CHANGED_AT.plusMinutes(1)));

            assertThat(states()).containsExactly("ACTIVE", "PAUSED");
            assertThat(changedAt()).isEqualTo(CHANGED_AT.plusMinutes(1));
        }

        @Test
        void 종료는_해당_캠페인의_회차만_끝내고_새_발급을_거절한다() throws Exception {
            event(82, 91, TODAY.minusDays(1), "ACTIVE");
            event(83, 91, TODAY.plusDays(1), "SCHEDULED");
            event(84, 92, TODAY, "ACTIVE");

            publish(new CampaignEndedEvent(91, CHANGED_AT));

            assertThat(states()).containsExactly("ENDED", "ENDED", "ENDED", "ACTIVE");
            performIssue(post("/v1/coupons").with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventId\":81}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON-002"));
        }

        @Test
        void 같은_시각의_중단_다음에_종료를_받아도_ENDED가_된다() {
            publish(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));

            publish(new CampaignEndedEvent(91, CHANGED_AT));

            assertThat(states()).containsExactly("ENDED");
            assertThat(changedAt()).isEqualTo(CHANGED_AT);
        }

        @Test
        void 종료_다음에_같은_시각의_중단을_받아도_ENDED를_유지한다() {
            publish(new CampaignEndedEvent(91, CHANGED_AT));

            publish(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));

            assertThat(states()).containsExactly("ENDED");
        }

        @Test
        void 종료된_회차는_더_늦은_재개_이벤트가_와도_되살리지_않는다() {
            publish(new CampaignEndedEvent(91, CHANGED_AT));
            publish(new CampaignStatusChangedEvent(91, TODAY, true, CHANGED_AT.plusMinutes(1)));

            assertThat(states()).containsExactly("ENDED");
            assertThat(changedAt()).isEqualTo(CHANGED_AT);
        }

        @Test
        void 같은_시각과_과거의_상태_이벤트는_최신_상태를_덮지_않는다() {
            publish(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));
            var before = jdbcTemplate.queryForMap("SELECT * FROM coupon_event WHERE coupon_event_id=81");
            doReturn(TODAY.atTime(12, 30).atZone(ClockConfig.ZONE).toInstant()).when(clock).instant();

            publish(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));
            publish(new CampaignStatusChangedEvent(91, TODAY, true, CHANGED_AT));
            publish(new CampaignStatusChangedEvent(91, TODAY, true, CHANGED_AT.minusMinutes(1)));
            publish(new CampaignEndedEvent(91, CHANGED_AT.minusMinutes(1)));

            assertThat(jdbcTemplate.queryForMap("SELECT * FROM coupon_event WHERE coupon_event_id=81")).isEqualTo(before);
        }

        @Test
        void 종료_이벤트를_다시_받아도_처음_종료_시각과_수정_시각을_유지한다() {
            publish(new CampaignEndedEvent(91, CHANGED_AT));
            var before = jdbcTemplate.queryForMap("SELECT * FROM coupon_event WHERE coupon_event_id=81");
            doReturn(TODAY.atTime(12, 30).atZone(ClockConfig.ZONE).toInstant()).when(clock).instant();

            publish(new CampaignEndedEvent(91, CHANGED_AT));
            publish(new CampaignEndedEvent(91, CHANGED_AT.plusMinutes(1)));

            assertThat(jdbcTemplate.queryForMap("SELECT * FROM coupon_event WHERE coupon_event_id=81")).isEqualTo(before);
        }

        @Test
        void 회차가_없는_캠페인의_이벤트는_회차를_만들지_않는다() {
            publish(new CampaignStatusChangedEvent(999, TODAY, false, CHANGED_AT));
            publish(new CampaignEndedEvent(999, CHANGED_AT));

            assertThat(states()).containsExactly("ACTIVE");
            assertThat(changedAt()).isNull();
        }

        @Test
        void 캠페인_트랜잭션이_롤백되면_회차_변경도_함께_되돌린다() {
            var before = jdbcTemplate.queryForMap("SELECT * FROM coupon_event WHERE coupon_event_id=81");

            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                publisher.publishEvent(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));
                publisher.publishEvent(new CampaignEndedEvent(91, CHANGED_AT));
                assertThat(states()).containsExactly("ENDED");
                assertThat(changedAt()).isEqualTo(CHANGED_AT);
                transaction.setRollbackOnly();
            });

            assertThat(jdbcTemplate.queryForMap("SELECT * FROM coupon_event WHERE coupon_event_id=81")).isEqualTo(before);
        }

        @Test
        void 회차_상태_저장이_실패하면_발행자_변경도_되돌리고_재시도하면_반영한다() {
            var event = new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT);
            jdbcTemplate.execute("ALTER TABLE coupon_event ADD CONSTRAINT chk_campaign_sync_failure CHECK (status='ACTIVE')");
            try {
                assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    jdbcTemplate.update("UPDATE coupon_event SET issue_quantity=3 WHERE coupon_event_id=81");
                    publisher.publishEvent(event);
                })).isInstanceOf(DataAccessException.class).rootCause().isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_campaign_sync_failure");

                assertThat(jdbcTemplate.queryForObject("SELECT issue_quantity FROM coupon_event WHERE coupon_event_id=81", Integer.class))
                        .isEqualTo(2);
                assertThat(states()).containsExactly("ACTIVE");
                assertThat(changedAt()).isNull();
            } finally {
                jdbcTemplate.execute("ALTER TABLE coupon_event DROP CHECK chk_campaign_sync_failure");
            }
            publish(event);

            assertThat(states()).containsExactly("PAUSED");
            assertThat(changedAt()).isEqualTo(CHANGED_AT);
        }

        @Test
        void 회차_종료_저장이_실패하면_발행자_변경도_되돌리고_재시도하면_반영한다() {
            var event = new CampaignEndedEvent(91, CHANGED_AT);
            jdbcTemplate.execute("ALTER TABLE coupon_event ADD CONSTRAINT chk_campaign_sync_failure CHECK (status='ACTIVE')");
            try {
                assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                    jdbcTemplate.update("UPDATE coupon_event SET issue_quantity=3 WHERE coupon_event_id=81");
                    publisher.publishEvent(event);
                })).isInstanceOf(DataAccessException.class).rootCause().isInstanceOf(SQLException.class)
                        .hasMessageContaining("chk_campaign_sync_failure");

                assertThat(jdbcTemplate.queryForObject("SELECT issue_quantity FROM coupon_event WHERE coupon_event_id=81", Integer.class))
                        .isEqualTo(2);
                assertThat(states()).containsExactly("ACTIVE");
                assertThat(changedAt()).isNull();
            } finally {
                jdbcTemplate.execute("ALTER TABLE coupon_event DROP CHECK chk_campaign_sync_failure");
            }
            publish(event);

            assertThat(states()).containsExactly("ENDED");
            assertThat(changedAt()).isEqualTo(CHANGED_AT);
        }

        @Test
        void 같은_회차에_이벤트가_동시에_와도_마지막_전이_시각의_상태가_남는다() throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<?> earlier = executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    publish(new CampaignStatusChangedEvent(91, TODAY, false, CHANGED_AT));
                    return null;
                });
                Future<?> later = executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    publish(new CampaignStatusChangedEvent(91, TODAY, true, CHANGED_AT.plusMinutes(1)));
                    return null;
                });
                start.countDown();
                earlier.get(10, TimeUnit.SECONDS);
                later.get(10, TimeUnit.SECONDS);
            }

            assertThat(states()).containsExactly("ACTIVE");
            assertThat(changedAt()).isEqualTo(CHANGED_AT.plusMinutes(1));
        }

        private LocalDateTime changedAt() {
            return jdbcTemplate.queryForObject("SELECT status_changed_at FROM coupon_event WHERE coupon_event_id=81",
                    LocalDateTime.class);
        }

        private void publish(Object event) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> publisher.publishEvent(event));
        }

        private List<String> states() {
            return jdbcTemplate.queryForList("SELECT status FROM coupon_event ORDER BY coupon_event_id", String.class);
        }

        private void event(long eventId, long campaignId, LocalDate businessDate, String state) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_event (coupon_event_id, campaign_id, business_date, issue_quantity,
                            usable_start_at, usable_end_at, status, store_id, store_name, discount_target_type,
                            discount_type, discount_value, created_at, updated_at)
                    VALUES (?, ?, ?, 2, ?, ?, ?, 10, '런치식당', 'ALL', 'AMOUNT', 3000, ?, ?)
                    """, eventId, campaignId, businessDate, businessDate.atTime(11, 0), businessDate.atTime(14, 0),
                    state, businessDate.atStartOfDay(), businessDate.atStartOfDay());
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
            stubActiveMembers();
            stubWishedMembers();
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

        @ParameterizedTest
        @EnumSource(value = CouponEventStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        void 처음_조회한_이벤트가_ACTIVE가_아니면_발급하지_않는다(CouponEventStatus eventStatus) {
            jdbcTemplate.update("UPDATE coupon_event SET status=? WHERE coupon_event_id=81", eventStatus.name());

            assertError(17L, 81L, CouponErrorCode.COUPON_NOT_AVAILABLE);

            assertConsistent(0);
            verifyNoInteractions(candidates);
        }

        @ParameterizedTest
        @EnumSource(value = CouponEventStatus.class, names = {"PAUSED", "ENDED"})
        void 캠페인_중단과_종료는_새_발급만_막고_기존_쿠폰과_재고와_한도를_보존한다(CouponEventStatus target) {
            assertThat(issue(17L, 81L)).isEqualTo("CREATED");
            var couponsBefore = jdbcTemplate.queryForList("SELECT * FROM member_coupon");
            var inventoryBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY sequence_no");
            var counterBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit");
            Object event = target == CouponEventStatus.ENDED
                    ? new CampaignEndedEvent(81, TODAY.atTime(12, 0))
                    : new CampaignStatusChangedEvent(81, TODAY, false, TODAY.atTime(12, 0));

            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> publisher.publishEvent(event));

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM coupon_event WHERE coupon_event_id=81", String.class))
                    .isEqualTo(target.name());
            assertError(18L, 81L, CouponErrorCode.COUPON_NOT_AVAILABLE);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM member_coupon"))
                    .usingRecursiveComparison().isEqualTo(couponsBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY sequence_no"))
                    .usingRecursiveComparison().isEqualTo(inventoryBefore);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(counterBefore);
            assertConsistent(1);
        }

        @Test
        void 캠페인이_재개되면_기존_발급_검사로_다시_쿠폰을_발급한다() {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                publisher.publishEvent(new CampaignStatusChangedEvent(81, TODAY, false, TODAY.atTime(11, 30)));
                publisher.publishEvent(new CampaignStatusChangedEvent(81, TODAY, true, TODAY.atTime(11, 31)));
            });

            assertThat(issue(17L, 81L)).isEqualTo("CREATED");
            assertConsistent(1);
        }

        @Test
        void 중단은_진행_중인_발급을_기다린_뒤_반영하고_새_발급을_거절한다() throws Exception {
            CountDownLatch checked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(wishes.findWishedMemberIds(81L, Set.of(17L))).thenAnswer(invocation -> {
                checked.countDown();
                await(release);
                return Set.of(17L);
            });
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> request = executor.submit(() -> issue(17L, 81L));
                assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> pause = executor.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(transaction -> publisher.publishEvent(
                                new CampaignStatusChangedEvent(81, TODAY, false, TODAY.atTime(12, 0)))));
                try {
                    assertThatThrownBy(() -> pause.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally {
                    release.countDown();
                }
                assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("CREATED");
                pause.get(5, TimeUnit.SECONDS);
            }
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM coupon_event WHERE coupon_event_id=81", String.class)).isEqualTo("PAUSED");
            assertError(18L, 81L, CouponErrorCode.COUPON_NOT_AVAILABLE);
            assertConsistent(1);
        }

        @Test
        void 카운터가_잠겨_있어도_찜하지_않은_요청은_기다리지_않는다() throws Exception {
            LocalDateTime now = TODAY.atTime(12, 0);
            jdbcTemplate.update("""
                    INSERT INTO coupon_daily_limit (member_id, issued_count, business_date, created_at, updated_at)
                    VALUES (17, 0, ?, ?, ?)
                    """, TODAY, now, now);
            when(wishes.findWishedMemberIds(81L, Set.of(17L))).thenReturn(Set.of());
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(transaction -> {
                            jdbcTemplate.queryForObject("""
                                    SELECT issued_count FROM coupon_daily_limit
                                     WHERE member_id=17 AND business_date=? FOR UPDATE
                                    """, Integer.class, TODAY);
                            locked.countDown();
                            await(release);
                        }));
                try {
                    assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                    Future<String> request = executor.submit(() -> issue(17L, 81L));
                    assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("COUPON-004");
                } finally {
                    release.countDown();
                }
                holder.get(5, TimeUnit.SECONDS);
            }
            assertConsistent(0);
        }

        @Test
        void 같은_이벤트의_다른_회원은_앞선_회원의_찜_검증을_기다리지_않는다() throws Exception {
            CountDownLatch firstChecked = new CountDownLatch(1);
            CountDownLatch secondChecked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(wishes.findWishedMemberIds(81L, Set.of(17L))).thenAnswer(invocation -> {
                firstChecked.countDown();
                await(release);
                return Set.of(17L);
            });
            when(wishes.findWishedMemberIds(81L, Set.of(18L))).thenAnswer(invocation -> {
                secondChecked.countDown();
                return Set.of(18L);
            });
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> first = executor.submit(() -> issue(17L, 81L));
                try {
                    assertThat(firstChecked.await(5, TimeUnit.SECONDS)).isTrue();
                    Future<String> second = executor.submit(() -> issue(18L, 81L));
                    assertThat(secondChecked.await(5, TimeUnit.SECONDS)).isTrue();
                    release.countDown();
                    assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("CREATED");
                    assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("CREATED");
                } finally {
                    release.countDown();
                }
            }
            assertConsistent(2);
        }

        @Test
        void 마지막_재고를_커밋한_뒤에만_트랜잭션_밖에서_소진_통보를_접수한다() {
            couponCommandService.issue(17L, 81L);
            couponCommandService.issue(18L, 81L);
            verifyNoInteractions(candidates);
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                assertConsistent(3);
                assertThat(invocation.<Long>getArgument(0)).isEqualTo(81L);
                return null;
            }).when(soldOutNotifications).request(81L);

            couponCommandService.issue(19L, 81L);

            verify(soldOutNotifications).request(81L);
        }

        @ParameterizedTest
        @CsvSource({"ACTIVE, false", "PAUSED, false", "ENDED, false", "ACTIVE, true"})
        void 확정_소진은_이벤트_상태와_발급_시간보다_먼저_DB_조회_없이_반환한다(String eventStatus, boolean closed) {
            couponCommandService.issue(17L, 81L);
            couponCommandService.issue(18L, 81L);
            couponCommandService.issue(19L, 81L);
            jdbcTemplate.update("UPDATE coupon_event SET status=? WHERE coupon_event_id=81", eventStatus);
            if (closed) {
                time.set(TODAY.atTime(14, 0).atZone(ClockConfig.ZONE).toInstant());
            }
            org.mockito.Mockito.clearInvocations(events, members, wishes, batchIssues);

            List<String> statements = SqlCapture.capture(
                    () -> assertError(20L, 81L, CouponErrorCode.INVENTORY_EXHAUSTED));

            assertThat(statements).isEmpty();
            verify(events, org.mockito.Mockito.never()).findAllForIssue(any());
            verify(members, org.mockito.Mockito.never()).findCurrentStatuses(any());
            verify(wishes, org.mockito.Mockito.never()).findWishedMemberIds(any(), any());
            verifyNoInteractions(batchIssues);
        }

        @Test
        void 마지막_발급과_후속_소진_거절은_통보를_접수하고_HTTP_결과를_유지한다() throws Exception {
            couponCommandService.issue(17L, 81L);
            couponCommandService.issue(18L, 81L);
            performIssue(post("/v1/coupons").with(user(new CustomUserDetails(19L, Role.MEMBER)))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":81}"))
                    .andExpect(status().isCreated());
            assertConsistent(3);
            time.set(Instant.parse("2026-10-10T03:05:00Z"));

            performIssue(post("/v1/coupons").with(user(new CustomUserDetails(20L, Role.MEMBER)))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":81}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COUPON-006"));

            verify(soldOutNotifications, times(2)).request(81L);
            verifyNoInteractions(candidates);
            assertConsistent(3);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM coupon_daily_limit WHERE member_id=20", Integer.class)).isZero();
        }

        @Test
        void 찜하지_않은_회원은_HTTP_403이며_발급_상태를_변경하지_않는다() throws Exception {
            when(wishes.findWishedMemberIds(81L, Set.of(17L))).thenReturn(Set.of());
            performIssue(post("/v1/coupons").with(user(MEMBER))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":81}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("COUPON-004"));
            assertConsistent(0);
            verifyNoInteractions(candidates);
        }

        @Test
        void 찜_조회_장애는_HTTP_500이며_발급을_롤백한다() throws Exception {
            when(wishes.findWishedMemberIds(81L, Set.of(17L)))
                    .thenThrow(new DataAccessResourceFailureException("wishlist unavailable"));
            performIssue(post("/v1/coupons").with(user(MEMBER))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":81}"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));
            assertConsistent(0);
            verifyNoInteractions(candidates);
        }

        @Test
        void 마지막_발급의_커밋이_실패하면_소진을_통보하지_않는다() {
            jdbcTemplate.update("DELETE FROM coupon_inventory WHERE sequence_no > 1");
            var failure = new DataAccessResourceFailureException("commit failed");
            Answer<?> real = mockingDetails(batchIssues).getMockCreationSettings().getDefaultAnswer();
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
            }).when(batchIssues).insertCoupons(anyList());

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
            performIssue(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
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
                batchIssuanceService.issueBatch(List.of(new CouponIssuanceRequest(17L, 81L, TODAY.atTime(12, 0))));
                assertThat(funnel.received).isEmpty();
                transaction.setRollbackOnly();
            });
            assertThat(funnel.received).isEmpty();
            verifyNoInteractions(candidates);
            assertConsistent(0);
        }

        @Test
        void HTTP로_발급한_쿠폰의_사용_이벤트는_같은_회원_쿠폰_ID를_싣는다() throws Exception {
            performIssue(post("/v1/coupons").with(user(MEMBER)).contentType("application/json")
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
            assertThat(results).allMatch(code -> List.of("CREATED", "COUPON-006", "COUPON-008").contains(code));
            assertConsistent(3);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT coupon_code) FROM member_coupon", Integer.class))
                    .isEqualTo(3);
        }

        @ParameterizedTest
        @CsvSource({"AVAILABLE, 2", "ISSUED, 1"})
        void 다른_이벤트와_상태의_재고를_제외하고_가장_작은_순번을_조회한다(
                CouponInventoryStatus inventoryStatus, int expectedSequence) {
            insertEvent(82L, 3);
            jdbcTemplate.update("""
                    UPDATE coupon_inventory SET status = 'ISSUED'
                     WHERE coupon_event_id = 81 AND sequence_no = 1
                    """);

            CouponInventory inventory = new TransactionTemplate(transactionManager).execute(transaction ->
                    inventories.findByCouponEventIdAndSequenceNo(81L, expectedSequence)
                            .orElseThrow());

            assertThat(inventory.getCouponEventId()).isEqualTo(81L);
            assertThat(inventory.getStatus()).isEqualTo(inventoryStatus);
            assertThat(inventory.getSequenceNo()).isEqualTo(expectedSequence);
        }

        @Test
        void 같은_회원의_동시_재요청은_한_장만_발급한다() throws Exception {
            List<String> results = concurrently(IntStream.range(0, 5).mapToObj(i -> new long[] {17L, 81L}).toList());
            assertThat(results.stream().filter("CREATED"::equals)).hasSize(1);
            assertThat(results).allMatch(code -> List.of("CREATED", "COUPON-005", "COUPON-008").contains(code));
            assertConsistent(1);
        }

        @ParameterizedTest
        @CsvSource({"true, false", "false, true", "true, true"})
        void 재고가_남으면_중복을_우선하고_전량_발급됐으면_소진을_반환한다(boolean dailyLimitReached, boolean soldOut) {
            couponCommandService.issue(17L, 81L);
            if (dailyLimitReached) {
                insertEvent(82L, 3);
                insertEvent(83L, 3);
                couponCommandService.issue(17L, 82L);
                couponCommandService.issue(17L, 83L);
            }
            if (soldOut) {
                couponCommandService.issue(18L, 81L);
                couponCommandService.issue(19L, 81L);
            }

            assertError(17L, 81L, soldOut ? CouponErrorCode.INVENTORY_EXHAUSTED : CouponErrorCode.EVENT_ALREADY_ISSUED);

            assertConsistent(1 + (dailyLimitReached ? 2 : 0) + (soldOut ? 2 : 0));
        }

        @Test
        void 다른_트랜잭션이_쿠폰을_잠가도_중복_발급_검사는_기다리지_않는다() throws Exception {
            couponCommandService.issue(17L, 81L);
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(transaction -> {
                            jdbcTemplate.queryForObject("""
                                    SELECT member_coupon_id FROM member_coupon
                                     FORCE INDEX (uk_member_coupon_event_member)
                                     WHERE coupon_event_id=81 AND member_id=17 FOR UPDATE
                                    """, Long.class);
                            locked.countDown();
                            await(release);
                        }));
                try {
                    assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                    Future<String> request = executor.submit(() -> issue(17L, 81L));
                    assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("COUPON-005");
                } finally {
                    release.countDown();
                }
                holder.get(5, TimeUnit.SECONDS);
            }
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
        @CsvSource({"event, false", "inventory, false", "flush, false",
                "event, true", "inventory, true", "flush, true"})
        void 각_저장_단계의_실패는_카운터와_재고와_쿠폰을_모두_롤백한다(String stage, boolean hasCounter) {
            if (hasCounter) {
                jdbcTemplate.update("""
                        INSERT INTO coupon_daily_limit (member_id, business_date, issued_count, created_at, updated_at)
                        VALUES (17, ?, 0, ?, ?)
                        """, TODAY, TODAY.atTime(12, 0), TODAY.atTime(12, 0));
            }
            var failure = new DataAccessResourceFailureException("발급 저장 실패");
            if (stage.equals("event")) {
                doThrow(failure).when(events).findAllForIssue(anyCollection());
            } else if (stage.equals("inventory")) {
                doThrow(failure).when(batchIssues)
                        .issueSequences(org.mockito.ArgumentMatchers.anyMap(), any());
            } else {
                Answer<?> real = mockingDetails(batchIssues).getMockCreationSettings().getDefaultAnswer();
                doAnswer(invocation -> {
                    real.answer(invocation);
                    throw failure;
                }).when(batchIssues).insertCoupons(anyList());
            }
            assertThatThrownBy(() -> couponCommandService.issue(17L, 81L)).isSameAs(failure);
            assertConsistent(0);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_daily_limit", Integer.class))
                    .isEqualTo(hasCounter ? 1 : 0);
            assertThat(funnel.received).isEmpty();
        }

        @Test
        void DB_조회가_실패하면_공통_서버_오류를_반환하고_롤백한다() throws Exception {
            doThrow(new QueryTimeoutException("DB 조회 실패")).when(events).findAllForIssue(anyCollection());
            performIssue(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
                            .contentType("application/json").content("{\"eventId\":81}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("COMMON-001"))
                    .andExpect(header().doesNotExist("Retry-After"));
            assertConsistent(0);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_daily_limit", Integer.class)).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"2026-10-10T03:30:00Z", "2026-10-10T04:00:00Z"})
        void 사용_종료_시각부터_HTTP_발급을_거절하고_재고와_한도를_보존한다(Instant now) throws Exception {
            jdbcTemplate.update("UPDATE coupon_event SET usable_end_at=? WHERE coupon_event_id=81", TODAY.atTime(12, 30));
            jdbcTemplate.update("UPDATE coupon_inventory SET usable_end_at=? WHERE coupon_event_id=81", TODAY.atTime(12, 30));
            var inventoryBefore = jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY sequence_no");
            time.set(now);

            performIssue(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
                            .contentType("application/json").content("{\"eventId\":81}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("COUPON-002"));

            assertConsistent(0);
            assertThat(jdbcTemplate.queryForList("SELECT * FROM coupon_inventory ORDER BY sequence_no"))
                    .usingRecursiveComparison().isEqualTo(inventoryBefore);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_daily_limit", Integer.class)).isZero();
            assertThat(funnel.received).isEmpty();
        }

        @Test
        void 재고_잠금_대기_중_발급_시간이_끝나도_발급한다() throws Exception {
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbcTemplate.queryForObject("""
                            SELECT coupon_inventory_id FROM coupon_inventory
                             WHERE coupon_event_id=81 AND sequence_no=1 FOR UPDATE
                            """, Long.class);
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
                assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo("CREATED");
                holder.get(5, TimeUnit.SECONDS);
            }
            assertConsistent(1);
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


        @Nested
        @DisplayName("실제 HTTP 쿠폰 발급")
        class Http {

            private static final AtomicLong NEXT_EVENT = new AtomicLong(70000);
            private long httpEventId;
            private String prefix;
            private final List<Map<String, Object>> exchanges = new CopyOnWriteArrayList<>();

            @BeforeEach
            void prepareMembers() {
                httpEventId = NEXT_EVENT.incrementAndGet();
                prefix = "coupon:{" + httpEventId + "}:";
                insertEvent(httpEventId, 3);
                jdbcTemplate.batchUpdate("""
                        INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at)
                        VALUES (?, ?, 'HTTP 발급 회원', 'ACTIVE', ?, ?)
                        """, IntStream.range(17, 29).mapToObj(id -> new Object[] {
                            id, "http-coupon-member-" + id, TODAY.atTime(12, 0), TODAY.atTime(12, 0)
                        }).toList());
                jdbcTemplate.batchUpdate("""
                        INSERT INTO wishlist (member_id,campaign_id,store_id,created_at,updated_at)
                        VALUES (?,?,10,?,?)
                        """, IntStream.range(17, 29).mapToObj(id -> new Object[] {
                            id, httpEventId, TODAY.atTime(12, 0), TODAY.atTime(12, 0)
                        }).toList());
                doCallRealMethod().when(members).findCurrentStatuses(any());
                doCallRealMethod().when(wishes).findWishedMemberIds(org.mockito.ArgumentMatchers.eq(httpEventId), any());
            }

            @AfterEach
            void recordResponsesAndCleanMembers(TestInfo info) throws Exception {
                Path directory = Path.of("build", "reports", "coupon-issuance-http");
                Files.createDirectories(directory);
                String filename = info.getTestMethod().orElseThrow().getName() + "-"
                        + Integer.toHexString(info.getDisplayName().hashCode()) + ".json";
                Files.writeString(directory.resolve(filename), objectMapper.writeValueAsString(
                        Map.of("case", info.getDisplayName(), "exchanges", exchanges)));
                redis.delete(List.of(prefix + "available", prefix + "pending", prefix + "meta"));
                sequenceService.resume(httpEventId);
                jdbcTemplate.update("DELETE FROM event_log WHERE member_id BETWEEN 17 AND 28");
                jdbcTemplate.update("DELETE FROM wishlist WHERE member_id BETWEEN 17 AND 28");
                jdbcTemplate.update("DELETE FROM member WHERE member_id BETWEEN 17 AND 28");
            }

            @Test
            void JWT_쿠키로_발급하면_201과_DB에_저장한_ID와_한국_시각을_받는다() throws Exception {
                try (HttpClient client = HttpClient.newHttpClient()) {
                    var response = send(client, 17L);
                    assertCreated(response, 17L);
                }
                assertConsistent(1);
            }

            @Test
            void Redis_초기화가_없어도_같은_HTTP_요청에서_복구하고_201을_받는다() throws Exception {
                redis.delete(List.of(prefix + "available", prefix + "pending", prefix + "meta"));
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertCreated(send(client, 17L), 17L);
                }
                assertConsistent(1);
                assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("READY");
            }

            @Test
            void 로컬_차단을_복구한_최초_HTTP_요청도_201로_응답한다() throws Exception {
                sequenceService.block(httpEventId, null);
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertCreated(send(client, 17L), 17L);
                }
                assertThat(sequenceService.needsRecovery(httpEventId)).isFalse();
                assertConsistent(1);
            }

            @Test
            void 오래된_예약을_복구한_HTTP_요청은_한_장만_발급한다() throws Exception {
                assertThat(sequences.reserveAll(httpEventId, List.of("lost-http-reservation"), 30000)).containsExactly(1L);
                redis.opsForZSet().add(prefix + "pending", "1:lost-http-reservation", 1);
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertCreated(send(client, 17L), 17L);
                }
                assertConsistent(1);
                assertThat(redis.opsForZSet().size(prefix + "pending")).isZero();
                assertThat(redis.opsForHash().get(prefix + "meta", "completed")).isEqualTo("1");
            }

            @Test
            void Redis_완료_응답이_유실돼도_HTTP_201을_유지하고_다음_요청도_복구해_발급한다() throws Exception {
                doAnswer(invocation -> {
                    invocation.callRealMethod();
                    throw new RedisConnectionFailureException("HTTP completion reply lost");
                }).doCallRealMethod().when(sequences)
                        .completeAll(org.mockito.ArgumentMatchers.eq(httpEventId), anyList(), anyList());
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertCreated(send(client, 17L), 17L);
                    assertThat(sequenceService.needsRecovery(httpEventId)).isTrue();
                    assertCreated(send(client, 18L), 18L);
                }
                assertThat(sequenceService.needsRecovery(httpEventId)).isFalse();
                assertConsistent(2);
            }

            @Test
            void 같은_이벤트의_재요청은_409와_중복_코드를_받는다() throws Exception {
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertCreated(send(client, 17L), 17L);
                    assertRejected(send(client, 17L), 409, "COUPON-005");
                }
                assertConsistent(1);
            }

            @Test
            void 세_장을_모두_발급하면_다음_HTTP_요청은_409와_소진_코드를_받는다() throws Exception {
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertCreated(send(client, 17L), 17L);
                    assertCreated(send(client, 18L), 18L);
                    assertCreated(send(client, 19L), 19L);
                    assertRejected(send(client, 20L), 409, "COUPON-006");
                }
                assertConsistent(3);
            }

            @Test
            void 찜하지_않은_회원의_HTTP_요청은_403과_자격_거절을_받는다() throws Exception {
                doReturn(Set.of()).when(wishes).findWishedMemberIds(any(), any());
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, 17L), 403, "COUPON-004");
                }
                assertConsistent(0);
            }

            @Test
            void 현재_회원_상태가_정지면_HTTP_403을_받고_발급하지_않는다() throws Exception {
                doReturn(Map.of(17L, MemberStatus.SUSPENDED)).when(members).findCurrentStatuses(any());
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, 17L), 403, "COUPON-003");
                }
                assertConsistent(0);
            }

            @Test
            void 일일_한도에_도달한_HTTP_요청은_409와_한도_거절을_받는다() throws Exception {
                jdbcTemplate.update("""
                        INSERT INTO coupon_daily_limit(member_id,business_date,issued_count,created_at,updated_at)
                        VALUES (17,?,3,?,?)
                        """, TODAY, TODAY.atTime(12, 0), TODAY.atTime(12, 0));
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, 17L), 409, "COUPON-007");
                }
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_coupon", Integer.class)).isZero();
                assertThat(jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_daily_limit WHERE member_id=17",
                        Integer.class)).isEqualTo(3);
            }

            @Test
            void 없는_이벤트의_HTTP_요청은_404를_받는다() throws Exception {
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, "{\"eventId\":999999999}", accessToken(Role.MEMBER, 17L)),
                            404, "COUPON-001");
                }
                assertConsistent(0);
            }

            @Test
            void 발급_종료_시각의_HTTP_요청은_409를_받는다() throws Exception {
                time.set(TODAY.atTime(14, 0).atZone(ClockConfig.ZONE).toInstant());
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, 17L), 409, "COUPON-002");
                }
                assertConsistent(0);
            }

            @ParameterizedTest
            @CsvSource(value = {"{}|COMMON-002", "{\"eventId\":0}|COMMON-002", "{\"eventId\":\"bad\"}|COMMON-003"}, delimiter = '|')
            void 잘못된_HTTP_본문은_400과_공통_오류를_받는다(String body, String code) throws Exception {
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, body, accessToken(Role.MEMBER, 17L)), 400, code);
                }
                assertConsistent(0);
            }

            @Test
            void 쿠키가_없거나_회원_역할이_아니면_HTTP_인증과_인가_오류를_받는다() throws Exception {
                when(ownerTokenPolicy.supports(Role.OWNER)).thenReturn(true);
                when(ownerTokenPolicy.isValid(any(), any(), any(), any(), any())).thenReturn(true);
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, requestBody(), null), 401, "AUTH-005");
                    assertRejected(send(client, requestBody(), accessToken(Role.OWNER, 17L)), 403, "AUTH-006");
                }
                assertConsistent(0);
            }

            @Test
            void DB_저장_장애는_HTTP_500으로_응답하고_발급과_예약을_되돌린다() throws Exception {
                doThrow(new DataAccessResourceFailureException("HTTP issuance storage unavailable"))
                        .when(batchIssues).insertCoupons(anyList());
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, 17L), 500, "COMMON-001");
                }
                assertConsistent(0);
                assertThat(redis.opsForList().size(prefix + "available")).isEqualTo(3);
                assertThat(redis.opsForZSet().size(prefix + "pending")).isZero();
            }

            @Test
            void Redis_복구에_실패하면_HTTP_503을_받고_발급하지_않는다() throws Exception {
                redis.delete(List.of(prefix + "available", prefix + "pending", prefix + "meta"));
                doThrow(new RedisConnectionFailureException("HTTP issuance recovery unavailable"))
                        .when(sequences).rebuild(org.mockito.ArgumentMatchers.eq(httpEventId), anyList(), anyLong(), anyLong());
                try (HttpClient client = HttpClient.newHttpClient()) {
                    assertRejected(send(client, 17L), 503, "COUPON-008");
                }
                assertConsistent(0);
            }

            @Test
            void 실제_HTTP_동시_요청_열두_건은_재고_세_장까지만_발급한다() throws Exception {
                CountDownLatch start = new CountDownLatch(1);
                try (HttpClient client = HttpClient.newHttpClient(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Future<HttpResponse<String>>> requests = IntStream.range(17, 29).mapToObj(id -> executor.submit(() -> {
                        start.await();
                        return send(client, id);
                    })).toList();
                    start.countDown();
                    Set<Long> couponIds = ConcurrentHashMap.newKeySet();
                    int successes = 0;
                    for (Future<HttpResponse<String>> request : requests) {
                        var response = request.get(15, TimeUnit.SECONDS);
                        if (response.statusCode() == 201) {
                            successes++;
                            var body = objectMapper.readTree(response.body());
                            assertThat(body.path("code").asText()).isEqualTo("SUCCESS");
                            assertThat(couponIds.add(body.path("data").path("memberCouponId").asLong())).isTrue();
                        } else {
                            assertThat(response.statusCode()).isIn(409, 503);
                            assertThat(objectMapper.readTree(response.body()).path("code").asText())
                                    .isIn("COUPON-006", "COUPON-008");
                        }
                    }
                    assertThat(successes).isEqualTo(3);
                }
                assertConsistent(3);
            }

            private String requestBody() {
                return "{\"eventId\":" + httpEventId + "}";
            }

            private HttpResponse<String> send(HttpClient client, long memberId) throws Exception {
                return send(client, requestBody(), accessToken(Role.MEMBER, memberId));
            }

            private HttpResponse<String> send(HttpClient client, String body, String token) throws Exception {
                long started = System.nanoTime();
                var response = client.send(httpRequest("POST", "/v1/coupons", body, token), HttpResponse.BodyHandlers.ofString());
                exchanges.add(Map.of("method", "POST", "path", "/v1/coupons", "request", body,
                        "status", response.statusCode(), "response", objectMapper.readTree(response.body()),
                        "elapsedMs", (System.nanoTime() - started) / 1_000_000.0));
                return response;
            }

            private void assertCreated(HttpResponse<String> response, long memberId) throws Exception {
                assertThat(response.statusCode()).isEqualTo(201);
                assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
                var body = objectMapper.readTree(response.body());
                assertThat(body.path("code").asText()).isEqualTo("SUCCESS");
                assertThat(body.path("message").asText()).isEqualTo("요청이 성공적으로 처리되었습니다.");
                var data = body.path("data");
                long couponId = data.path("memberCouponId").asLong();
                assertThat(couponId).isPositive();
                assertThat(data.path("issueTime").asText()).isEqualTo("2026-10-10T12:00:00+09:00");
                var saved = jdbcTemplate.queryForMap("SELECT member_id, coupon_event_id, created_at FROM member_coupon WHERE member_coupon_id=?", couponId);
                assertThat(((Number) saved.get("member_id")).longValue()).isEqualTo(memberId);
                assertThat(((Number) saved.get("coupon_event_id")).longValue()).isEqualTo(httpEventId);
                assertThat(saved.get("created_at")).isEqualTo(TODAY.atTime(12, 0));
            }

            private void assertRejected(HttpResponse<String> response, int status, String code) throws Exception {
                assertThat(response.statusCode()).isEqualTo(status);
                assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
                var body = objectMapper.readTree(response.body());
                assertThat(body.path("code").asText()).isEqualTo(code);
                assertThat(body.path("message").asText()).isNotBlank();
                assertThat(body.path("data").isNull()).isTrue();
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
            sequences.rebuild(eventId, IntStream.rangeClosed(1, quantity).boxed().toList(), 0, Instant.now().plusSeconds(3600).toEpochMilli());
            sequenceService.resume(eventId);
        }
    }

    @Nested
    @DisplayName("Redis 순번 예약과 복구")
    class SequenceRecovery {

        private static final AtomicLong NEXT_EVENT = new AtomicLong(80000);
        private long eventId;
        private long memberId;
        private String prefix;

        // 재고 세 장짜리 이벤트를 만들고 모든 회원을 ACTIVE이자 찜한 회원으로 고정한 뒤 Redis 큐를 DB 기준으로 채운다.
        @BeforeEach
        void prepare() {
            eventId = NEXT_EVENT.incrementAndGet();
            memberId = eventId * 10;
            prefix = "coupon:{" + eventId + "}:";
            LocalDate today = TODAY;
            jdbcTemplate.update("""
                    INSERT INTO coupon_event(coupon_event_id,campaign_id,business_date,issue_quantity,
                        usable_start_at,usable_end_at,status,store_id,store_name,discount_target_type,
                        discount_type,discount_value,created_at,updated_at)
                    VALUES (?,?,?,3,?,?,'ACTIVE',10,'발급 시험','ALL','AMOUNT',1000,?,?)
                    """, eventId, eventId, today, today.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                    today.atStartOfDay(), today.atStartOfDay());
            for (int sequence = 1; sequence <= 3; sequence++) {
                jdbcTemplate.update("""
                        INSERT INTO coupon_inventory(coupon_event_id,sequence_no,coupon_code,status,
                            usable_start_at,usable_end_at,created_at,updated_at)
                        VALUES (?,?,UUID_TO_BIN(UUID()),'AVAILABLE',?,?,?,?)
                        """, eventId, sequence, today.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                        today.atStartOfDay(), today.atStartOfDay());
            }
            stubActiveMembers();
            stubWishedMembers();
            sequenceRecoveryService.recover(eventId);
            assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("READY");
        }

        // 테스트가 만든 이벤트, 재고, 쿠폰, 카운터와 Redis 키를 지우고 차단 표시를 푼다.
        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM member_coupon WHERE coupon_event_id=?", eventId);
            jdbcTemplate.update("DELETE FROM coupon_inventory WHERE coupon_event_id=?", eventId);
            jdbcTemplate.update("DELETE FROM coupon_event WHERE coupon_event_id=?", eventId);
            jdbcTemplate.update("DELETE FROM coupon_daily_limit WHERE member_id BETWEEN ? AND ?", memberId, memberId + 20);
            redis.delete(List.of(prefix + "available", prefix + "pending", prefix + "meta"));
            sequenceService.resume(eventId);
        }

        // 발급, 중복 거절, 남은 재고 발급, 소진 거절을 차례로 거친 뒤 Redis 완료 수와 DB 쿠폰 수가 3으로 맞아야 한다.
        @Test
        void 발급과_중복_거절_후_실패한_순번을_다른_회원에게_반납한다() {
            couponCommandService.issue(memberId, eventId);
            assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("READY");
            assertThatThrownBy(() -> couponCommandService.issue(memberId, eventId)).isInstanceOfSatisfying(CouponException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.EVENT_ALREADY_ISSUED));
            couponCommandService.issue(memberId + 1, eventId);
            couponCommandService.issue(memberId + 2, eventId);
            assertThatThrownBy(() -> couponCommandService.issue(memberId + 3, eventId)).isInstanceOfSatisfying(CouponException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED));
            assertThat(redis.opsForHash().get(prefix + "meta", "completed")).isEqualTo("3");
            assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("SOLD_OUT");
            assertThat(redis.opsForZSet().size(prefix + "pending")).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_coupon WHERE coupon_event_id=?",
                    Integer.class, eventId)).isEqualTo(3);
        }

        @Test
        void 전량_발급한_이벤트는_직접_예약해도_소진으로_거절하고_중복_완료는_무시한다() {
            couponCommandService.issue(memberId, eventId);
            couponCommandService.issue(memberId + 1, eventId);
            couponCommandService.issue(memberId + 2, eventId);

            assertThat(sequences.reserveAll(eventId, List.of("late-one", "late-two"), 30000)).containsExactly(0L, 0L);
            assertThat(sequences.complete(eventId, 3, "already-completed")).isFalse();
            assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("SOLD_OUT");
            assertThat(redis.opsForHash().get(prefix + "meta", "completed")).isEqualTo("3");
        }

        @Test
        void 전량_발급한_이벤트를_DB로_복구하면_SOLD_OUT으로_재구축한다() {
            couponCommandService.issue(memberId, eventId);
            couponCommandService.issue(memberId + 1, eventId);
            couponCommandService.issue(memberId + 2, eventId);
            sequenceService.block(eventId, null);

            assertThat(sequenceRecoveryService.recover(eventId)).isTrue();

            assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("SOLD_OUT");
            assertThat(sequenceService.isConfirmedSoldOut(eventId)).isTrue();
        }

        @Test
        void 이전_READY_형식의_전량_발급_정보도_소진으로_읽고_예약_시_SOLD_OUT으로_맞춘다() {
            couponCommandService.issue(memberId, eventId);
            couponCommandService.issue(memberId + 1, eventId);
            couponCommandService.issue(memberId + 2, eventId);
            redis.opsForHash().put(prefix + "meta", "state", "READY");

            assertThat(sequenceService.isConfirmedSoldOut(eventId)).isTrue();
            assertThat(sequences.reserveAll(eventId, List.of("legacy"), 30000)).containsExactly(0L);
            assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("SOLD_OUT");
        }

        @Test
        void SOLD_OUT인데_발급_완료_수가_부족하면_소진으로_믿지_않고_예약을_차단한다() {
            redis.opsForHash().put(prefix + "meta", "state", "SOLD_OUT");

            assertThat(sequenceService.isConfirmedSoldOut(eventId)).isFalse();
            assertThat(sequences.reserveAll(eventId, List.of("inconsistent"), 30000)).containsExactly(-1L);
            assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("BLOCKED");
        }

        // 묶음 경로는 자격 판정을 통과한 요청만 순번을 예약하므로 거절된 요청은 큐를 건드리지 않는다.
        @Test
        void 자격_검증에_실패하면_순번을_예약하지_않는다() {
            doReturn(Set.of()).when(wishes).findWishedMemberIds(any(), any());
            assertThatThrownBy(() -> couponCommandService.issue(memberId, eventId)).isInstanceOfSatisfying(CouponException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_WISHED));
            assertThat(redis.opsForList().range(prefix + "available", 0, -1)).containsExactly("1", "2", "3");
            assertThat(redis.opsForZSet().size(prefix + "pending")).isZero();
        }

        // 이미 반납한 예약의 반납이 늦게 한 번 더 와도, 그 순번을 새로 예약한 사람이 있으면 큐에 다시 넣지 않아야 한다.
        @Test
        void 오래된_반납이_다시_예약한_순번을_중복으로_넣지_않는다() {
            reserve("old");
            sequences.release(eventId, 1, "old");
            reserve("two");
            reserve("three");
            assertThat(reserve("new")).isEqualTo(1);
            sequences.release(eventId, 1, "old");
            assertThat(redis.opsForList().size(prefix + "available")).isZero();
            assertThat(redis.opsForZSet().score(prefix + "pending", "1:new")).isNotNull();
        }

        /*
         * 커밋 뒤 Redis 완료 응답을 잃어도 발급 성공은 유지하고 이벤트를 차단한다.
         * 다음 요청이 DB 기준으로 큐를 다시 만들어 발급된 순번이 빠져야 한다.
         */
        @Test
        void 완료_응답_유실은_발급_성공을_유지하고_후속_요청에서_DB로_복구한다() {
            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw new RedisConnectionFailureException("completion reply lost");
            }).doCallRealMethod().when(sequences)
                    .completeAll(org.mockito.ArgumentMatchers.eq(eventId), anyList(), anyList());
            assertThat(couponCommandService.issue(memberId, eventId).memberCouponId()).isPositive();
            assertThat(sequenceService.needsRecovery(eventId)).isTrue();
            assertThat(couponCommandService.issue(memberId + 1, eventId).memberCouponId()).isPositive();
            assertThat(redis.opsForHash().get(prefix + "meta", "completed")).isEqualTo("2");
            assertThat(redis.opsForList().range(prefix + "available", 0, -1)).containsExactly("3");
            assertThat(sequenceService.needsRecovery(eventId)).isFalse();
        }

        // 오래 남은 예약을 발견하면 그 예약자에게 순번을 넘기지 않고 큐를 차단한 뒤 DB의 남은 재고로 다시 만들어야 한다.
        @Test
        void 오래된_예약은_이전_예약자에게_돌려주지_않고_DB의_남은_재고로_복구한다() {
            reserve("lost-response");
            redis.opsForZSet().add(prefix + "pending", "1:lost-response", 1);
            assertThat(couponCommandService.issue(memberId, eventId).memberCouponId()).isPositive();
            assertThat(redis.opsForList().range(prefix + "available", 0, -1)).containsExactly("2", "3");
            assertThat(redis.opsForZSet().size(prefix + "pending")).isZero();
            assertThat(couponCommandService.issue(memberId + 1, eventId).memberCouponId()).isPositive();
        }

        // 큐를 다시 만들기 전에 받은 예약의 완료나 반납은 새 큐를 바꾸지 않아야 한다.
        @Test
        void 재구축_이전의_예약_완료와_반납은_새_큐를_바꾸지_않는다() {
            reserve("before-rebuild");
            sequenceService.block(eventId, null);
            sequenceRecoveryService.recover(eventId);
            sequences.complete(eventId, 1, "before-rebuild");
            sequences.release(eventId, 1, "before-rebuild");
            assertThat(redis.opsForList().range(prefix + "available", 0, -1)).containsExactly("1", "2", "3");
            assertThat(redis.opsForHash().get(prefix + "meta", "completed")).isEqualTo("0");
        }

        // 남은 순번이 모두 다른 요청의 처리 중이면 소진이나 큐 복구가 아니라 잠시 바쁨(ISSUANCE_BUSY)으로 응답해야 한다.
        @Test
        void 모든_재고가_처리_중이면_소진이나_전체_복구로_바꾸지_않는다() {
            reserve("one");
            reserve("two");
            reserve("three");
            assertThatThrownBy(() -> couponCommandService.issue(memberId, eventId)).isInstanceOfSatisfying(CouponException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));
            assertThat(redis.opsForZSet().size(prefix + "pending")).isEqualTo(3);
            assertThat(sequenceService.needsRecovery(eventId)).isFalse();
            assertThat(redis.opsForHash().get(prefix + "meta", "state")).isEqualTo("READY");
        }

        // Redis 키가 통째로 사라지면 발급을 막고 DB 기준으로 다시 만들어 이미 발급된 순번은 빠져야 한다.
        @Test
        void Redis_키가_사라져도_DB에_커밋된_순번을_큐에_다시_넣지_않는다() {
            couponCommandService.issue(memberId, eventId);
            redis.delete(List.of(prefix + "available", prefix + "pending", prefix + "meta"));
            assertThat(couponCommandService.issue(memberId + 1, eventId).memberCouponId()).isPositive();
            assertThat(redis.opsForList().range(prefix + "available", 0, -1)).containsExactly("3");
            assertThat(redis.opsForHash().get(prefix + "meta", "completed")).isEqualTo("2");
        }

        // 열두 명이 동시에 요청해도 재고 세 장만 발급되고 나머지는 소진이나 바쁨으로 거절돼야 한다.
        @Test
        void 여러_요청이_함께_발급해도_세_장을_초과하지_않는다() throws Exception {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<String>> jobs = IntStream.range(0, 12).mapToObj(i -> executor.submit(() -> {
                    try {
                        couponCommandService.issue(memberId + i, eventId);
                        return "CREATED";
                    } catch (CouponException error) {
                        return error.getErrorCode().getCode();
                    }
                })).toList();
                List<String> results = new ArrayList<>();
                for (Future<String> job : jobs) {
                    results.add(job.get(10, TimeUnit.SECONDS));
                }
                assertThat(results).filteredOn("CREATED"::equals).hasSize(3);
                assertThat(results).isSubsetOf("CREATED", "COUPON-006", "COUPON-008");
            }
            assertThat(redis.opsForHash().get(prefix + "meta", "completed")).isEqualTo("3");
        }

        /*
         * 발급 묶음이 순번을 예약한 뒤 재고를 갱신하기 직전에 멈춘 사이 큐를 차단하고 복구를 시작한다.
         * 복구의 배타 잠금은 진행 중인 발급의 공유 잠금이 풀릴 때까지 기다려야 하고 그 뒤에 커밋된 결과를 읽어야 한다.
         */
        @Test
        void 복구는_다른_서버의_DB_발급이_끝난_뒤_커밋_결과를_읽는다() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            CountDownLatch recovering = new CountDownLatch(1);
            doAnswer(invocation -> {
                entered.countDown();
                assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
                return invocation.callRealMethod();
            }).when(batchIssues).issueSequences(org.mockito.ArgumentMatchers.anyMap(), any());
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<CouponIssueResponse> issuing = executor.submit(() -> couponCommandService.issue(memberId, eventId));
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                sequenceService.block(eventId, null);
                Future<?> rebuilding = executor.submit(() -> {
                    recovering.countDown();
                    sequenceRecoveryService.recover(eventId);
                });
                try {
                    assertThat(recovering.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> rebuilding.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } finally {
                    finish.countDown();
                }
                assertThat(issuing.get(10, TimeUnit.SECONDS).memberCouponId()).isPositive();
                rebuilding.get(10, TimeUnit.SECONDS);
            }
            assertThat(redis.opsForList().range(prefix + "available", 0, -1)).containsExactly("2", "3");
            assertThat(redis.opsForHash().get(prefix + "meta", "completed")).isEqualTo("1");
            assertThat(redis.opsForZSet().size(prefix + "pending")).isZero();
        }

        @Test
        void 재구축과_예약_완료_반납은_같은_절대_만료_시각을_유지한다() {
            long expiresAt = Instant.now().plusSeconds(60).toEpochMilli();
            sequences.rebuild(eventId, List.of(1, 2, 3), 0, expiresAt);
            assertThat(expiration("available")).isEqualTo(expiresAt);
            assertThat(expiration("meta")).isEqualTo(expiresAt);
            assertThat(reserve("one")).isEqualTo(1);
            assertThat(expiration("pending")).isEqualTo(expiresAt);
            reserve("two");
            reserve("three");
            assertThat(redis.hasKey(prefix + "available")).isFalse();

            sequences.release(eventId, 1, "one");
            assertThat(expiration("available")).isEqualTo(expiresAt);
            sequences.complete(eventId, 2, "two");
            sequenceService.block(eventId, null);
            assertThat(expiration("meta")).isEqualTo(expiresAt);
            assertThat(expiration("pending")).isEqualTo(expiresAt);
            sequences.rebuild(eventId, List.of(1, 3), 1, expiresAt);
            assertThat(expiration("available")).isEqualTo(expiresAt);
            assertThat(expiration("meta")).isEqualTo(expiresAt);
        }

        @Test
        void 만료된_예약의_완료_반납_차단과_복구는_키를_되살리지_않는다() {
            reserve("expired");
            for (String suffix : List.of("available", "pending", "meta")) {
                redis.expireAt(prefix + suffix, Instant.EPOCH);
            }

            sequences.complete(eventId, 1, "expired");
            sequences.release(eventId, 1, "expired");
            sequenceService.block(eventId, null);
            assertThatThrownBy(() -> sequences.rebuild(eventId, List.of(1, 2, 3), 0,
                    Instant.now().minusSeconds(1).toEpochMilli())).rootCause().isInstanceOf(IllegalStateException.class);

            assertThat(redis.hasKey(prefix + "available")).isFalse();
            assertThat(redis.hasKey(prefix + "pending")).isFalse();
            assertThat(redis.hasKey(prefix + "meta")).isFalse();
        }

        @Test
        void 재구축이_시작한_뒤_만료돼도_나머지_적재로_키를_다시_만들지_않는다() {
            var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>();
            script.setResultType(Long.class);
            script.setLocation(new org.springframework.core.io.ClassPathResource("scripts/coupon/rebuild.lua"));
            List<String> keys = List.of(prefix + "available", prefix + "pending", prefix + "meta");
            long expiresAt = Instant.now().plusSeconds(60).toEpochMilli();
            String expiry = Long.toString(expiresAt);
            assertThat(redis.execute(script, keys, "start", expiry, "3", "0")).isEqualTo(1L);
            redis.expireAt(prefix + "meta", Instant.EPOCH);

            assertThat(redis.execute(script, keys, "append", expiry, "1", "2", "3")).isZero();
            assertThat(redis.execute(script, keys, "finish", expiry, "3")).isZero();
            assertThat(redis.hasKey(prefix + "available")).isFalse();
            assertThat(redis.hasKey(prefix + "meta")).isFalse();
        }

        private Long expiration(String suffix) {
            return redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "return redis.call('PEXPIRETIME', KEYS[1])", Long.class), List.of(prefix + suffix));
        }

        // 순번 하나를 Redis에 바로 예약하고 결과 부호를 돌려준다.
        private long reserve(String reservationId) {
            return sequences.reserveAll(eventId, List.of(reservationId), 30000).getFirst();
        }
    }

    @Nested
    @DisplayName("묶음 쿠폰 발급")
    class BatchIssuance {

        private static final AtomicLong NEXT_EVENT = new AtomicLong(90000);
        private final List<Long> events = new ArrayList<>();
        private final Set<Long> notWished = ConcurrentHashMap.newKeySet();
        private long memberBase;

        // 테스트마다 서로 겹치지 않는 회원 ID 구간을 정하고 회원 상태와 찜 조회를 고정한다.
        @BeforeEach
        void prepare() {
            memberBase = NEXT_EVENT.get() * 100;
            stubActiveMembers();
            doAnswer(invocation -> invocation.<Set<Long>>getArgument(1).stream()
                    .filter(id -> !notWished.contains(id)).collect(Collectors.toSet()))
                    .when(wishes).findWishedMemberIds(any(), any());
        }

        // 테스트가 만든 이벤트와 재고, 쿠폰, 카운터와 Redis 키를 지우고 차단 표시를 푼다.
        @AfterEach
        void cleanUp() {
            for (long eventId : events) {
                jdbcTemplate.update("DELETE FROM member_coupon WHERE coupon_event_id=?", eventId);
                jdbcTemplate.update("DELETE FROM coupon_inventory WHERE coupon_event_id=?", eventId);
                jdbcTemplate.update("DELETE FROM coupon_event WHERE coupon_event_id=?", eventId);
                String prefix = "coupon:{" + eventId + "}:";
                redis.delete(List.of(prefix + "available", prefix + "pending", prefix + "meta"));
                sequenceService.resume(eventId);
            }
            jdbcTemplate.update("DELETE FROM coupon_daily_limit WHERE member_id BETWEEN ? AND ?", memberBase, memberBase + 99);
        }

        // 재고보다 많은 회원이 동시에 요청하면 재고만큼만 발급되고 DB와 Redis의 수가 맞아야 한다.
        @Test
        void 여러_회원이_동시에_요청해도_재고만큼만_발급하고_카운터와_코드가_맞는다() throws Exception {
            long eventId = event(12);
            Map<String, Integer> results = concurrently(40, i -> couponCommandService.issue(memberBase + i, eventId));

            assertThat(results.get("ISSUED")).isEqualTo(12);
            assertThat(results.keySet()).isSubsetOf("ISSUED", CouponErrorCode.INVENTORY_EXHAUSTED.name(),
                    CouponErrorCode.ISSUANCE_BUSY.name());
            assertConsistent(eventId, 12);
        }

        /*
         * 같은 회원의 동시 요청은 한 장만 발급되고 나머지는 중복으로 거절돼야 한다.
         * 거절된 요청이 순번을 잡지 않으므로 남은 재고가 모두 Redis 목록에 그대로 남아야 한다.
         */
        @Test
        void 같은_회원의_동시_요청은_한_장만_발급하고_나머지는_중복으로_거절한다() throws Exception {
            long eventId = event(5);
            Map<String, Integer> results = concurrently(10, i -> couponCommandService.issue(memberBase, eventId));

            assertThat(results).containsEntry("ISSUED", 1)
                    .containsEntry(CouponErrorCode.EVENT_ALREADY_ISSUED.name(), 9);
            assertConsistent(eventId, 1);
            assertThat(redis.opsForList().size("coupon:{" + eventId + "}:available")).isEqualTo(4);
        }

        // 한 회원이 다섯 이벤트에 동시에 요청하면 일일 한도 세 장까지만 발급돼야 한다.
        @Test
        void 한_회원이_여러_이벤트에_동시에_요청해도_일일_한도까지만_발급한다() throws Exception {
            List<Long> ids = List.of(event(2), event(2), event(2), event(2), event(2));
            Map<String, Integer> results = concurrently(5, i -> couponCommandService.issue(memberBase, ids.get(i)));

            assertThat(results).containsEntry("ISSUED", 3)
                    .containsEntry(CouponErrorCode.DAILY_LIMIT_REACHED.name(), 2);
            assertThat(jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_daily_limit WHERE member_id=?",
                    Integer.class, memberBase)).isEqualTo(3);
        }

        /*
         * 대기열을 거치지 않고 묶음 하나를 직접 넘겨 묶음 안의 판정 순서를 확인한다.
         * 찜하지 않은 회원과 같은 묶음 안의 중복 요청은 거절되고 나머지 두 건만 발급돼야 한다.
         * 거절된 요청은 순번을 잡지 않으므로 Redis 목록에 세 장이 남고 대기 중인 예약은 없어야 한다.
         */
        @Test
        void 한_묶음_안의_거절은_다른_요청의_발급을_막지_않고_순번을_돌려놓는다() {
            long eventId = event(5);
            notWished.add(memberBase + 1);
            LocalDateTime now = TODAY.atTime(12, 0);
            List<CouponIssuanceOutcome> outcomes = batchIssuanceService.issueBatch(List.of(
                    new CouponIssuanceRequest(memberBase, eventId, now),
                    new CouponIssuanceRequest(memberBase + 1, eventId, now),
                    new CouponIssuanceRequest(memberBase, eventId, now),
                    new CouponIssuanceRequest(memberBase + 2, eventId, now)));

            assertThat(outcomes.get(0).response()).isNotNull();
            assertThat(outcomes.get(1).rejection()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_WISHED);
            assertThat(outcomes.get(2).rejection()).isEqualTo(CouponErrorCode.EVENT_ALREADY_ISSUED);
            assertThat(outcomes.get(3).response()).isNotNull();
            outcomes.stream().filter(outcome -> outcome.reservation() != null)
                    .forEach(outcome -> sequenceService.complete(outcome.reservation()));
            assertConsistent(eventId, 2);
            assertThat(redis.opsForList().size("coupon:{" + eventId + "}:available")).isEqualTo(3);
            assertThat(redis.opsForZSet().size("coupon:{" + eventId + "}:pending")).isZero();
        }

        @Test
        void 일괄_상태_조회에서_정지와_탈퇴와_없는_회원은_각각_거절한다() {
            long eventId = event(4);
            Set<Long> memberIds = Set.of(memberBase, memberBase + 1, memberBase + 2, memberBase + 3);
            doReturn(Map.of(memberBase, MemberStatus.ACTIVE, memberBase + 1, MemberStatus.SUSPENDED,
                    memberBase + 2, MemberStatus.WITHDRAWN)).when(members).findCurrentStatuses(memberIds);
            LocalDateTime now = TODAY.atTime(12, 0);

            List<CouponIssuanceOutcome> outcomes = batchIssuanceService.issueBatch(List.of(
                    new CouponIssuanceRequest(memberBase, eventId, now),
                    new CouponIssuanceRequest(memberBase + 1, eventId, now),
                    new CouponIssuanceRequest(memberBase + 2, eventId, now),
                    new CouponIssuanceRequest(memberBase + 3, eventId, now)));

            assertThat(outcomes.getFirst().response()).isNotNull();
            assertThat(outcomes.subList(1, 4)).extracting(CouponIssuanceOutcome::rejection)
                    .containsExactly(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED,
                            CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED, CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
            sequenceService.complete(outcomes.getFirst().reservation());
            assertConsistent(eventId, 1);
        }

        @Test
        void 한_묶음의_캠페인마다_찜한_회원에게만_발급한다() {
            long firstEvent = event(2);
            long secondEvent = event(2);
            Set<Long> memberIds = Set.of(memberBase, memberBase + 1);
            doReturn(Set.of(memberBase)).when(wishes).findWishedMemberIds(firstEvent, memberIds);
            doReturn(Set.of(memberBase + 1)).when(wishes).findWishedMemberIds(secondEvent, memberIds);
            LocalDateTime now = TODAY.atTime(12, 0);

            List<CouponIssuanceOutcome> outcomes = batchIssuanceService.issueBatch(List.of(
                    new CouponIssuanceRequest(memberBase, firstEvent, now),
                    new CouponIssuanceRequest(memberBase + 1, firstEvent, now),
                    new CouponIssuanceRequest(memberBase, secondEvent, now),
                    new CouponIssuanceRequest(memberBase + 1, secondEvent, now)));

            assertThat(outcomes.get(0).response()).isNotNull();
            assertThat(outcomes.get(1).rejection()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_WISHED);
            assertThat(outcomes.get(2).rejection()).isEqualTo(CouponErrorCode.CAMPAIGN_NOT_WISHED);
            assertThat(outcomes.get(3).response()).isNotNull();
            sequenceService.complete(outcomes.get(0).reservation());
            sequenceService.complete(outcomes.get(3).reservation());
            assertThat(jdbcTemplate.queryForList("""
                    SELECT coupon_event_id FROM member_coupon WHERE member_id IN (?,?) ORDER BY member_id
                    """, Long.class, memberBase, memberBase + 1)).containsExactly(firstEvent, secondEvent);
            assertThat(jdbcTemplate.queryForList("""
                    SELECT coupon_event_id FROM coupon_inventory
                     WHERE coupon_event_id IN (?,?) AND status='ISSUED' ORDER BY coupon_event_id
                    """, Long.class, firstEvent, secondEvent)).containsExactly(firstEvent, secondEvent);
            assertThat(jdbcTemplate.queryForList("""
                    SELECT issued_count FROM coupon_daily_limit WHERE member_id IN (?,?) ORDER BY member_id
                    """, Integer.class, memberBase, memberBase + 1)).containsExactly(1, 1);
            assertThat(redis.opsForHash().get("coupon:{" + firstEvent + "}:meta", "completed")).isEqualTo("1");
            assertThat(redis.opsForHash().get("coupon:{" + secondEvent + "}:meta", "completed")).isEqualTo("1");
        }

        // 오늘 발급 가능한 이벤트와 재고를 quantity장 만들고 Redis 큐를 DB 기준으로 채운다.
        private long event(int quantity) {
            long eventId = NEXT_EVENT.incrementAndGet();
            events.add(eventId);
            LocalDate today = TODAY;
            jdbcTemplate.update("""
                    INSERT INTO coupon_event(coupon_event_id,campaign_id,business_date,issue_quantity,
                        usable_start_at,usable_end_at,status,store_id,store_name,discount_target_type,
                        discount_type,discount_value,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,'ACTIVE',10,'묶음 발급 시험','ALL','AMOUNT',1000,?,?)
                    """, eventId, eventId, today, quantity, today.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                    today.atStartOfDay(), today.atStartOfDay());
            for (int sequence = 1; sequence <= quantity; sequence++) {
                jdbcTemplate.update("""
                        INSERT INTO coupon_inventory(coupon_event_id,sequence_no,coupon_code,status,
                            usable_start_at,usable_end_at,created_at,updated_at)
                        VALUES (?,?,UUID_TO_BIN(UUID()),'AVAILABLE',?,?,?,?)
                        """, eventId, sequence, today.atStartOfDay(), today.plusDays(1).atStartOfDay(),
                        today.atStartOfDay(), today.atStartOfDay());
            }
            sequenceRecoveryService.recover(eventId);
            assertThat(redis.opsForHash().get("coupon:{" + eventId + "}:meta", "state")).isEqualTo("READY");
            return eventId;
        }

        /*
         * count개 스레드가 준비를 마친 뒤 동시에 action을 실행하고 결과별 건수를 돌려준다.
         * 발급에 성공하면 ISSUED로, CouponException이면 오류 코드 이름으로 센다.
         */
        private Map<String, Integer> concurrently(int count, IndexedAction action) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(count);
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            Map<String, Integer> results = new ConcurrentHashMap<>();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    String result;
                    try {
                        action.run(index);
                        result = "ISSUED";
                    } catch (CouponException error) {
                        result = String.valueOf(error.getErrorCode());
                    }
                    results.merge(result, 1, Integer::sum);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            executor.shutdown();
            return results;
        }

        // 회원 쿠폰, ISSUED 재고, 쿠폰 코드 연결, 일일 카운터 합, Redis 완료 수가 모두 발급 수와 같은지 확인한다.
        private void assertConsistent(long eventId, int issued) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_coupon WHERE coupon_event_id=?",
                    Integer.class, eventId)).isEqualTo(issued);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_inventory WHERE coupon_event_id=? AND status='ISSUED'",
                    Integer.class, eventId)).isEqualTo(issued);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM member_coupon coupon
                      JOIN coupon_inventory inventory ON inventory.coupon_code = coupon.coupon_code
                     WHERE coupon.coupon_event_id=? AND inventory.coupon_event_id=? AND inventory.status='ISSUED'
                    """, Integer.class, eventId, eventId)).isEqualTo(issued);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(issued_count), 0) FROM coupon_daily_limit WHERE member_id BETWEEN ? AND ?
                    """, Integer.class, memberBase, memberBase + 99)).isEqualTo(issued);
            assertThat(redis.opsForHash().get("coupon:{" + eventId + "}:meta", "completed")).isEqualTo(String.valueOf(issued));
        }

        // 몇 번째 스레드인지를 받아 요청을 보내는 동작이다.
        @FunctionalInterface
        private interface IndexedAction {
            void run(int index);
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
            when(stores.canManageStore(17L, 10L)).thenReturn(true);
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
        void 현재_관리할_수_없는_매장의_이력은_HTTP_403으로_거절한다() throws Exception {
            when(stores.canManageStore(17L, 10L)).thenReturn(false);
            mockMvc.perform(get(PATH).with(user(new CustomUserDetails(17L, Role.OWNER)))
                            .param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @Test
        void 매장_권한_조회_장애는_HTTP_500으로_전파한다() throws Exception {
            when(stores.canManageStore(17L, 10L)).thenThrow(new DataAccessResourceFailureException("store unavailable"));
            mockMvc.perform(get(PATH).with(user(new CustomUserDetails(17L, Role.OWNER)))
                            .param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));
        }

        @Test
        void 요청한_매장만_조회하며_시작일_시작은_포함하고_종료일_다음날_시작은_제외한다() {
            var response = ownerCouponService.getUsages(17L, 10L, FROM, TO, 20, null);

            assertThat(response.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L, 5002L);
            assertThat(response.nextPageToken()).isNull();
            assertThat(response.items().get(1).discountAmount()).isEqualTo(2700);
            assertThat(response.items().get(1).useTime()).isEqualTo(USE_TIME.atOffset(ZoneOffset.ofHours(9)));
        }

        @Test
        void 같은_사용_시각의_이력도_페이지에서_누락하지_않으며_새_이력은_기존_커서에_영향을_주지_않는다() {
            var first = ownerCouponService.getUsages(17L, 10L, FROM, TO, 1, null);
            insertHistory(5008L, 10L, USE_TIME.plusMinutes(1), 2500);

            var second = ownerCouponService.getUsages(17L, 10L, FROM, TO, 1, first.nextPageToken());
            var third = ownerCouponService.getUsages(17L, 10L, FROM, TO, 1, second.nextPageToken());

            assertThat(first.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5004L);
            assertThat(second.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5003L);
            assertThat(third.items()).extracting(CouponUsageHistoryResponse::usageHistoryId).containsExactly(5002L);
            assertThat(third.nextPageToken()).isNull();
        }

        @Test
        void 같은_날_조회에서는_그날의_사용_이력만_반환한다() {
            var singleDay = ownerCouponService.getUsages(17L, 10L, TO, TO, 100, null);

            assertThat(singleDay.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L);
        }

        @Test
        void 조회_기간_길이에_상한_없이_여러_해의_사용_이력을_반환한다() {
            var multipleYears = ownerCouponService.getUsages(17L, 10L, FROM.minusYears(3), TO, 100, null);

            assertThat(multipleYears.items()).extracting(CouponUsageHistoryResponse::usageHistoryId)
                    .containsExactly(5004L, 5003L, 5002L, 5001L, 5007L);
        }

        @Test
        void 이력이_없는_본인_매장은_빈_목록을_반환한다() {
            when(stores.canManageStore(17L, 30L)).thenReturn(true);
            var response = ownerCouponService.getUsages(17L, 30L, FROM, TO, 20, null);

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
            when(stores.canManageStore(71L, 10L)).thenReturn(true);

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

            ownerCouponService.getUsages(17L, 10L, FROM, TO, 1, null);

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
            when(stores.findAllById(any())).thenAnswer(invocation -> {
                List<Long> ids = invocation.getArgument(0);
                return ids.stream().map(id -> new StoreInfo(id, "매장", null, 37.5, 127.0)).toList();
            });
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
        void 현재_찜한_회원에게_실제_발급_가능_상태를_안내한다() throws Exception {
            when(wishes.findWishedCampaignIds(18L)).thenReturn(List.of(501L));
            mockMvc.perform(post(PATH).with(user(new CustomUserDetails(18L, Role.MEMBER)))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"storeIds\":[10]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].wished").value(true))
                    .andExpect(jsonPath("$.data.items[0].canIssue").value(true))
                    .andExpect(jsonPath("$.data.items[0].cannotIssueReason").value(org.hamcrest.Matchers.nullValue()));
        }

        @Test
        void 미존재와_등록_전_매장은_제외하며_모두_없으면_빈_목록을_반환한다() throws Exception {
            when(stores.findAllById(List.of(20L, 999L, 10L))).thenReturn(List.of(
                    new StoreInfo(10L, "매장1", null, 37.5, 127.0), new StoreInfo(20L, "매장2", null, 37.5, 127.0)));
            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeIds\":[20,999,10]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
                    .andExpect(jsonPath("$.data.items[0].storeId").value(20))
                    .andExpect(jsonPath("$.data.items[1].storeId").value(10));
            when(stores.findAllById(List.of(999L))).thenReturn(List.of());
            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeIds\":[999]}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        }

        @ParameterizedTest
        @ValueSource(strings = {"store", "wishlist"})
        void 공개_조회_장애를_빈_결과로_바꾸지_않는다(String source) throws Exception {
            var failure = new DataAccessResourceFailureException("contract unavailable");
            if (source.equals("store")) {
                when(stores.findAllById(List.of(10L))).thenThrow(failure);
            } else {
                when(wishes.findWishedCampaignIds(17L)).thenThrow(failure);
            }
            mockMvc.perform(post(PATH).with(user(MEMBER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeIds\":[10]}"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("COMMON-001"));
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
        void 찜하지_않은_캠페인은_발급_가능으로_안내하지_않는다() throws Exception {
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
        @EnumSource(value = CouponEventStatus.class, names = {"PAUSED", "ENDED"})
        void 캠페인이_중단되거나_끝나도_기존_쿠폰의_QR을_갱신하고_사용한다(CouponEventStatus target) throws Exception {
            var before = jdbcTemplate.queryForMap("SELECT * FROM member_coupon WHERE member_coupon_id=1001");
            Object event = target == CouponEventStatus.ENDED
                    ? new CampaignEndedEvent(81, NOW)
                    : new CampaignStatusChangedEvent(81, TODAY, false, NOW);

            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> publisher.publishEvent(event));

            assertThat(jdbcTemplate.queryForObject("SELECT status FROM coupon_event WHERE coupon_event_id=81", String.class))
                    .isEqualTo(target.name());
            assertThat(jdbcTemplate.queryForMap("SELECT * FROM member_coupon WHERE member_coupon_id=1001"))
                    .usingRecursiveComparison().isEqualTo(before);
            mockMvc.perform(get("/v1/coupons/1001").with(user(MEMBER)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.usable").value(true));
            mockMvc.perform(post("/v1/coupons/1001:generateQr").with(user(MEMBER)))
                    .andExpect(status().isOk());
            currentTime.set(NOW.plusSeconds(1).atZone(ClockConfig.ZONE).toInstant());
            String token = jdbcTemplate.queryForObject(
                    "SELECT BIN_TO_UUID(qr_token) FROM member_coupon WHERE member_coupon_id=1001", String.class);
            mockMvc.perform(post(PATH).with(user(OWNER)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"longitude\":127.0,\"latitude\":37.5,\"qrToken\":\"" + token + "\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.data.discountAmount").value(3000));
            assertThat(state()).isEqualTo("USED");
            assertThat(historyCount()).isEqualTo(1);
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
                var response = client.send(httpRequest("POST", PATH, body(), accessToken(Role.OWNER, 9L)), HttpResponse.BodyHandlers.ofString());
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

        // 시연 영상 촬영용 30km 제한을 검증하며 촬영 후 기존 100m 기준으로 복원한다.
        @ParameterizedTest
        @CsvSource({"37.501,201", "37.7697,201", "37.7699,403"})
        void 실제_HTTP로_30km_안팎의_사용_결과와_저장_상태를_확인한다(double latitude, int expectedStatus) throws Exception {
            when(stores.findAllById(List.of(10L))).thenReturn(List.of(store(latitude)));

            try (HttpClient client = HttpClient.newHttpClient()) {
                var response = client.send(httpRequest("POST", PATH, body(), accessToken(Role.OWNER, 9L)),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(expectedStatus);
                assertThat(objectMapper.readTree(response.body()).path("code").asText())
                        .isEqualTo(expectedStatus == 201 ? "SUCCESS" : "COUPON-009");
            }
            assertThat(state()).isEqualTo(expectedStatus == 201 ? "USED" : "ISSUED");
            assertThat(historyCount()).isEqualTo(expectedStatus == 201 ? 1 : 0);
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
            HttpRequest request = httpRequest("POST", PATH, body(), accessToken(Role.OWNER, 9L));
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

    @Nested
    @DisplayName("점주 캠페인 쿠폰 수량 조회")
    class OwnerSummaryQuery {

        private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
        private static final CustomUserDetails OWNER = new CustomUserDetails(7L, Role.OWNER);
        private static final String PATH = "/v1/owner/campaigns/501/coupons/summary";

        @BeforeEach
        void allowOwner() {
            when(ownerStatusQuery.findCurrentStatus(7L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            var campaign = mock(CampaignInfo.class);
            when(campaign.ownerId()).thenReturn(7L);
            when(campaignQueryService.findAllById(List.of(501L))).thenReturn(List.of(campaign));
            when(ownerTokenPolicy.supports(Role.OWNER)).thenReturn(true);
            when(ownerTokenPolicy.isValid(any(), anyLong(), any(), any(), any())).thenReturn(true);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history WHERE member_coupon_id IN (SELECT member_coupon_id FROM member_coupon WHERE coupon_event_id BETWEEN 81 AND 84)");
            jdbcTemplate.update("DELETE FROM member_coupon WHERE coupon_event_id BETWEEN 81 AND 84");
            jdbcTemplate.update("DELETE FROM coupon_inventory WHERE coupon_event_id BETWEEN 81 AND 84");
            jdbcTemplate.update("DELETE FROM coupon_event WHERE coupon_event_id BETWEEN 81 AND 84");
        }

        @Test
        void 실제_HTTP로_쿠폰_수량과_사용_이력을_조회한다() throws Exception {
            when(stores.canManageStore(7L, 20L)).thenReturn(true);
            event(81, 501, DAY, 50, "ACTIVE");
            for (int sequence = 1; sequence <= 24; sequence++) {
                if (sequence <= 12) {
                    use(81, sequence, DAY.atTime(12, 0));
                } else {
                    issue(81, sequence, sequence <= 16 ? "EXPIRED" : sequence <= 20 ? "REMOVED" : "ISSUED");
                }
            }

            String dates = "?from=" + DAY + "&to=" + DAY;
            try (HttpClient client = HttpClient.newHttpClient()) {
                String token = accessToken(Role.OWNER, 7L);
                var response = client.send(httpRequest("GET", PATH + dates, null, token), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var result = objectMapper.readTree(response.body());
                assertThat(result.path("code").asText()).isEqualTo("SUCCESS");
                var data = result.path("data");
                assertThat(data.path("campaignId").asLong()).isEqualTo(501);
                assertThat(data.path("from").asText()).isEqualTo(DAY.toString());
                assertThat(data.path("to").asText()).isEqualTo(DAY.toString());
                assertThat(data.path("totalQuantity").asLong()).isEqualTo(50);
                assertThat(data.path("issuedCount").asLong()).isEqualTo(24);
                assertThat(data.path("usedCount").asLong()).isEqualTo(12);
                assertThat(data.path("remainingQuantity").asLong()).isEqualTo(26);

                var history = client.send(httpRequest("GET", "/v1/owner/stores/20/coupon-usages" + dates, null, token),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(history.statusCode()).isEqualTo(200);
                var items = objectMapper.readTree(history.body()).path("data").path("items");
                assertThat(items.size()).isEqualTo(12);
                assertThat(items.get(0).path("discountAmount").asInt()).isEqualTo(2000);
            }

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM member_coupon WHERE status = 'ISSUED'", Long.class))
                    .isEqualTo(4);
        }

        @Test
        void 기간에_속한_여러_회차를_상태와_관계없이_합산하고_다른_캠페인은_제외한다() throws Exception {
            event(81, 501, DAY, 10, "ENDED");
            event(82, 501, DAY.plusDays(1), 20, "PAUSED");
            event(83, 501, DAY.plusDays(2), 30, "SCHEDULED");
            event(84, 502, DAY, 40, "ACTIVE");
            issue(81, 1, "REMOVED");
            use(82, 1, DAY.plusDays(1).atTime(12, 0));
            use(84, 1, DAY.atTime(12, 0));

            mockMvc.perform(get(PATH).with(user(OWNER)).param("from", DAY.toString()).param("to", DAY.plusDays(1).toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalQuantity").value(30))
                    .andExpect(jsonPath("$.data.issuedCount").value(2))
                    .andExpect(jsonPath("$.data.usedCount").value(1))
                    .andExpect(jsonPath("$.data.remainingQuantity").value(28));
        }

        @Test
        void 사용은_발급_회차의_날짜와_별개로_한국_날짜의_자정_이상_다음_자정_미만을_센다() throws Exception {
            event(81, 501, DAY.minusDays(1), 4, "ENDED");
            use(81, 1, DAY.atStartOfDay().minusNanos(1000));
            use(81, 2, DAY.atStartOfDay());
            use(81, 3, DAY.plusDays(1).atStartOfDay().minusNanos(1000));
            use(81, 4, DAY.plusDays(1).atStartOfDay());

            mockMvc.perform(request().with(user(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalQuantity").value(0))
                    .andExpect(jsonPath("$.data.issuedCount").value(0))
                    .andExpect(jsonPath("$.data.usedCount").value(2))
                    .andExpect(jsonPath("$.data.remainingQuantity").value(0));
        }

        @Test
        void 회차가_없으면_미래_예정_수량을_계산하지_않고_모든_수량을_0으로_반환한다() throws Exception {
            mockMvc.perform(request().with(user(OWNER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalQuantity").value(0))
                    .andExpect(jsonPath("$.data.issuedCount").value(0))
                    .andExpect(jsonPath("$.data.usedCount").value(0))
                    .andExpect(jsonPath("$.data.remainingQuantity").value(0));
        }

        @Test
        void 다른_점주의_캠페인과_없는_캠페인은_같은_오류로_거절한다() throws Exception {
            var other = mock(CampaignInfo.class);
            when(other.ownerId()).thenReturn(8L);
            when(campaignQueryService.findAllById(List.of(501L))).thenReturn(List.of(other), List.of());

            for (int request = 0; request < 2; request++) {
                mockMvc.perform(request().with(user(OWNER)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("COUPON-011"));
            }
        }

        @ParameterizedTest
        @EnumSource(value = OwnerAccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        void 토큰의_역할이_점주여도_현재_상태가_비활성이면_거절한다(OwnerAccountStatus state) throws Exception {
            when(ownerStatusQuery.findCurrentStatus(7L)).thenReturn(Optional.of(state));

            mockMvc.perform(request().with(user(OWNER)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @Test
        void 날짜_누락과_잘못된_형식은_거절한다() throws Exception {
            mockMvc.perform(get(PATH).with(user(OWNER)).param("from", DAY.toString()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
            mockMvc.perform(get(PATH).with(user(OWNER)).param("from", "invalid").param("to", DAY.toString()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-003"));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 31})
        void 뒤집힌_기간과_32일_이상_조회는_거절한다(int days) throws Exception {
            mockMvc.perform(get(PATH).with(user(OWNER)).param("from", DAY.toString()).param("to", DAY.plusDays(days).toString()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        @Test
        void 양수가_아닌_캠페인_ID는_거절한다() throws Exception {
            mockMvc.perform(get("/v1/owner/campaigns/0/coupons/summary").with(user(OWNER))
                            .param("from", DAY.toString()).param("to", DAY.toString()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON-002"));
        }

        private MockHttpServletRequestBuilder request() {
            return get(PATH).param("from", DAY.toString()).param("to", DAY.toString());
        }

        private void event(long id, long campaignId, LocalDate day, int quantity, String state) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_event
                        (coupon_event_id, campaign_id, business_date, issue_quantity, usable_start_at, usable_end_at,
                         status, store_id, store_name, discount_target_type, discount_type, discount_value, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 20, '테스트 매장', 'ALL', 'AMOUNT', 2000, ?, ?)
                    """, id, campaignId, day, quantity, day.atTime(11, 30), day.atTime(15, 0), state,
                    day.atStartOfDay(), day.atStartOfDay());
            for (int sequence = 1; sequence <= quantity; sequence++) {
                jdbcTemplate.update("""
                        INSERT INTO coupon_inventory
                            (coupon_event_id, sequence_no, coupon_code, status, usable_start_at, usable_end_at, created_at, updated_at)
                        VALUES (?, ?, UNHEX(LPAD(HEX(?), 32, '0')), 'AVAILABLE', ?, ?, ?, ?)
                        """, id, sequence, id * 1000 + sequence, day.atTime(11, 30), day.atTime(15, 0),
                        day.atStartOfDay(), day.atStartOfDay());
            }
        }

        private void issue(long eventId, int sequence, String state) {
            jdbcTemplate.update("""
                    UPDATE coupon_inventory SET status = 'ISSUED' WHERE coupon_event_id = ? AND sequence_no = ?
                    """, eventId, sequence);
            jdbcTemplate.update("""
                    INSERT INTO member_coupon
                        (member_coupon_id, coupon_event_id, member_id, coupon_code, status,
                         usable_start_at, usable_end_at, created_at, updated_at)
                    SELECT ?, coupon_event_id, ?, coupon_code, ?, usable_start_at, usable_end_at, created_at, updated_at
                      FROM coupon_inventory WHERE coupon_event_id = ? AND sequence_no = ?
                    """, eventId * 1000 + sequence, sequence, state, eventId, sequence);
        }

        private void use(long eventId, int sequence, LocalDateTime usedAt) {
            issue(eventId, sequence, "USED");
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history (member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (?, 20, 2000, ?, ?)
                    """, eventId * 1000 + sequence, usedAt, usedAt);
        }
    }

    @Nested
    @DisplayName("매장의 날짜별 쿠폰 사용 건수 조회")
    class DailyUsageQuery {

        private static final LocalDate FROM = LocalDate.of(2026, 10, 14);
        private static final LocalDate TO = LocalDate.of(2026, 10, 17);
        private static final CustomUserDetails OWNER = new CustomUserDetails(7L, Role.OWNER);
        private static final String PATH = "/v1/owner/stores/20/coupon-usages/daily";

        @BeforeEach
        void allowOwner() {
            when(ownerStatusQuery.findCurrentStatus(7L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            when(stores.canManageStore(7L, 20L)).thenReturn(true);
            when(ownerTokenPolicy.supports(Role.OWNER)).thenReturn(true);
            when(ownerTokenPolicy.isValid(any(), anyLong(), any(), any(), any())).thenReturn(true);
        }

        @AfterEach
        void cleanUp() {
            jdbcTemplate.update("DELETE FROM coupon_usage_history WHERE coupon_usage_history_id BETWEEN 61000 AND 61100");
            jdbcTemplate.update("DELETE FROM member_coupon WHERE member_coupon_id BETWEEN 61000 AND 61100");
            jdbcTemplate.update("DELETE FROM coupon_event WHERE coupon_event_id BETWEEN 61000 AND 61100");
        }

        @Test
        void 실제_HTTP로_여러_캠페인의_사용을_사용일로_합산하고_0건_날짜를_포함한다() throws Exception {
            insertUsage(61001, 20, 501, FROM, FROM.atStartOfDay().minusNanos(1000), 2000);
            insertUsage(61002, 20, 501, FROM.minusDays(3), FROM.atStartOfDay(), 2000);
            insertUsage(61003, 20, 502, FROM, FROM.atTime(12, 0), 0);
            insertUsage(61004, 20, 503, FROM, FROM.plusDays(2).atTime(12, 0), 2000);
            insertUsage(61005, 20, 502, FROM.plusDays(1), TO.plusDays(1).atStartOfDay().minusNanos(1000), 2000);
            insertUsage(61006, 20, 502, FROM.plusDays(2), TO.plusDays(1).atStartOfDay(), 2000);
            insertUsage(61007, 30, 504, FROM, FROM.atTime(12, 0), 2000);

            try (HttpClient client = HttpClient.newHttpClient()) {
                var response = client.send(httpRequest("GET", PATH + "?from=" + FROM + "&to=" + TO,
                        null, accessToken(Role.OWNER, 7L)), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var result = objectMapper.readTree(response.body());
                assertThat(result.path("code").asText()).isEqualTo("SUCCESS");
                var data = result.path("data");
                assertThat(data.path("storeId").asLong()).isEqualTo(20);
                assertThat(data.path("from").asText()).isEqualTo(FROM.toString());
                assertThat(data.path("to").asText()).isEqualTo(TO.toString());
                assertThat(data.path("totalUsedCount").asLong()).isEqualTo(4);
                var items = data.path("items");
                assertThat(items.size()).isEqualTo(4);
                long[] expected = {2, 0, 1, 1};
                for (int day = 0; day < expected.length; day++) {
                    assertThat(items.get(day).path("businessDate").asText()).isEqualTo(FROM.plusDays(day).toString());
                    assertThat(items.get(day).path("usedCount").asLong()).isEqualTo(expected[day]);
                }
            }
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM coupon_usage_history WHERE coupon_usage_history_id BETWEEN 61000 AND 61100", Long.class))
                    .isEqualTo(7);
        }

        @Test
        void 하루_조회는_그날만_반환하고_이력이_없어도_31일을_모두_반환한다() throws Exception {
            insertUsage(61001, 20, 501, FROM, FROM.atTime(12, 0), 2000);
            mockMvc.perform(get(PATH).with(user(OWNER)).param("from", FROM.toString()).param("to", FROM.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalUsedCount").value(1))
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].businessDate").value(FROM.toString()))
                    .andExpect(jsonPath("$.data.items[0].usedCount").value(1));
            mockMvc.perform(get(PATH).with(user(OWNER)).param("from", "2026-01-01").param("to", "2026-01-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalUsedCount").value(0))
                    .andExpect(jsonPath("$.data.items.length()").value(31))
                    .andExpect(jsonPath("$.data.items[0].usedCount").value(0))
                    .andExpect(jsonPath("$.data.items[30].businessDate").value("2026-01-31"));
        }

        @ParameterizedTest
        @ValueSource(longs = {20, 404})
        void 다른_점주_매장과_없는_매장은_같은_오류로_거절한다(long storeId) throws Exception {
            insertUsage(61001, 20, 501, FROM, FROM.atTime(12, 0), 2000);
            when(stores.canManageStore(7L, storeId)).thenReturn(false);
            mockMvc.perform(get("/v1/owner/stores/{storeId}/coupon-usages/daily", storeId).with(user(OWNER))
                            .param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COUPON-011"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @ParameterizedTest
        @EnumSource(value = OwnerAccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        void 현재_점주_상태가_비활성이면_거절한다(OwnerAccountStatus state) throws Exception {
            when(ownerStatusQuery.findCurrentStatus(7L)).thenReturn(Optional.of(state));
            mockMvc.perform(get(PATH).with(user(OWNER)).param("from", FROM.toString()).param("to", TO.toString()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
        }

        @ParameterizedTest
        @CsvSource({
                "0, 2026-10-14, 2026-10-17, COMMON-002",
                "20, , 2026-10-17, COMMON-003",
                "20, 2026-10-14, , COMMON-003",
                "20, invalid, 2026-10-17, COMMON-003",
                "20, 2026-10-17, 2026-10-14, COMMON-002",
                "20, 2026-10-01, 2026-11-01, COMMON-002"
        })
        void 잘못된_ID와_날짜_범위는_HTTP에서_거절한다(String storeId, String from, String to, String code) throws Exception {
            var request = get("/v1/owner/stores/{storeId}/coupon-usages/daily", storeId).with(user(OWNER));
            if (from != null) {
                request.param("from", from);
            }
            if (to != null) {
                request.param("to", to);
            }
            mockMvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
        }

        private void insertUsage(long id, long storeId, long campaignId, LocalDate eventDay, LocalDateTime usedAt, int discount) {
            jdbcTemplate.update("""
                    INSERT INTO coupon_event
                        (coupon_event_id, campaign_id, business_date, issue_quantity, usable_start_at, usable_end_at,
                         status, store_id, store_name, discount_target_type, discount_type, discount_value, created_at, updated_at)
                    VALUES (?, ?, ?, 1, ?, ?, 'ENDED', ?, '테스트 매장', 'ALL', 'AMOUNT', 2000, ?, ?)
                    """, id, campaignId, eventDay, eventDay.atTime(11, 30), eventDay.atTime(15, 0), storeId,
                    eventDay.atStartOfDay(), eventDay.atStartOfDay());
            jdbcTemplate.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code, status,
                        usable_start_at, usable_end_at, created_at, updated_at)
                    VALUES (?, ?, 17, UNHEX(LPAD(HEX(?), 32, '0')), 'USED', ?, ?, ?, ?)
                    """, id, id, id, eventDay.atTime(11, 30), eventDay.atTime(15, 0),
                    eventDay.atTime(11, 0), eventDay.atTime(11, 0));
            jdbcTemplate.update("""
                    INSERT INTO coupon_usage_history
                        (coupon_usage_history_id, member_coupon_id, store_id, discount_amount, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, id, storeId, discount, usedAt, usedAt);
        }
    }

    @Nested
    @DisplayName("점주 쿠폰 API 공통 HTTP 인증")
    class OwnerHttpAuthorization {

        @BeforeEach
        void setUpMember() {
            jdbcTemplate.update("""
                    INSERT INTO member (member_id, provider_user_id, nickname, status, created_at, updated_at)
                    VALUES (17, 'coupon-owner-http-test', '테스트 회원', 'ACTIVE', ?, ?)
                    """, TODAY.atStartOfDay(), TODAY.atStartOfDay());
        }

        @AfterEach
        void cleanUpMember() {
            jdbcTemplate.update("DELETE FROM member WHERE member_id = 17");
        }

        @ParameterizedTest
        @CsvSource({
                "GET, /v1/owner/campaigns/501/coupons/summary?from=2026-10-17&to=2026-10-17",
                "GET, /v1/owner/stores/20/coupon-usages?from=2026-10-17&to=2026-10-17",
                "GET, /v1/owner/stores/20/coupon-usages/daily?from=2026-10-17&to=2026-10-17",
                "POST, /v1/owner/coupon-usages"
        })
        void 실제_HTTP로_미인증과_회원의_점주_API_접근을_차단한다(String method, String path) throws Exception {
            String body = method.equals("POST")
                    ? "{\"longitude\":127.0,\"latitude\":37.5,\"qrToken\":\"6c75d5bc-cae3-4eeb-a573-fc3bcb1673a5\"}" : null;
            try (HttpClient client = HttpClient.newHttpClient()) {
                var anonymous = client.send(httpRequest(method, path, body, null), HttpResponse.BodyHandlers.ofString());
                assertThat(anonymous.statusCode()).isEqualTo(401);
                assertThat(objectMapper.readTree(anonymous.body()).path("code").asText()).isEqualTo("AUTH-005");
                var member = client.send(httpRequest(method, path, body, accessToken(Role.MEMBER, 17L)),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(member.statusCode()).isEqualTo(403);
                assertThat(objectMapper.readTree(member.body()).path("code").asText()).isEqualTo("AUTH-006");
            }
        }
    }

    private HttpRequest httpRequest(String method, String path, String body, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Cookie", "accessToken=" + token);
        }
        return request.build();
    }

    private String accessToken(Role role, long id) {
        Instant now = Instant.now();
        return Jwts.builder().subject(Long.toString(id)).claim("role", role.name())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).compact();
    }
    @Nested
    @DisplayName("쿠폰 독립 배치")
    class Batch {
        private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
        @Autowired CouponPreparationJob preparationJob;
        @Autowired CouponIssueOpenJob openJob;
        @Autowired CouponIssueCloseJob closeJob;
        @Autowired CouponExpirationJob expirationJob;
        @Autowired CouponConsistencyJob consistencyJob;
        @Autowired CouponPreparationService preparation;
        @Autowired BatchExecutionService executions;
        @Autowired JdbcTemplate jdbc;
        @BeforeEach
        void setUp() {
            cleanBatchData();
            when(retryBackoff.pause(org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
            funnel.received.clear();
            at(DATE.atTime(3, 0));
            CampaignInfo campaign = campaign();
            when(campaignQueryService.findServableOn(any())).thenReturn(List.of(campaign));
            when(campaignQueryService.findByIdForUpdate(anyLong())).thenReturn(Optional.of(campaign));
            when(stores.findAllById(List.of(10L)))
                    .thenReturn(List.of(new StoreInfo(10L, "쿠폰 식당", null, 37.5, 127.0)));
            when(stores.findMenusByIdIn(List.of(100L)))
                    .thenReturn(new StoreMenuQueryResult(List.of(new StoreMenuInfo(100L, 10L, "image", "점심", 4500L)), List.of()));
        }


        @Test
        void 선행_배치_확인_없이_재고를_적재하고_같은_날의_중복_실행을_막는다() {
            preparationJob.prepareCoupons();
            preparationJob.prepareCoupons();
            assertThat(count("coupon_event")).isEqualTo(1);
            assertThat(count("coupon_inventory")).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT target_menu_discounted_price FROM coupon_event", Long.class))
                    .isEqualTo(3820);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_execution_log WHERE job_name='DAILY_0000'", Integer.class))
                    .isZero();
        }

        @Test
        void 오픈과_마감은_기존_쿠폰과_캠페인_운영_상태를_바꾸지_않는다() {
            preparationJob.prepareCoupons();
            at(DATE.atTime(11, 0));
            openJob.openCoupons();
            openJob.openCoupons();
            issue(1, 17, "ISSUED");
            at(DATE.atTime(14, 0));
            closeJob.closeCoupons();
            assertThat(jdbc.queryForObject("SELECT status FROM coupon_event", String.class)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT status FROM member_coupon", String.class)).isEqualTo("ISSUED");
            assertThat(jdbc.queryForObject("SELECT status FROM batch_execution_log WHERE job_name='COUPON_CLOSE'", String.class))
                    .isEqualTo("SUCCESS");
            assertThat(funnel.received).filteredOn(CouponIssueOpenedEvent.class::isInstance).hasSize(1);
        }

        @Test
        void 조회에서_먼저_만료된_쿠폰은_배치가_다시_이벤트를_발행하지_않는다() {
            preparationJob.prepareCoupons();
            issue(1, 17, "ISSUED");
            at(DATE.atTime(15, 0));
            couponLookUpService.getDetail(17L, 1L);
            couponCommandService.deleteCoupon(17L, 1L);
            expirationJob.expireCoupons();
            assertThat(funnel.received).filteredOn(CouponExpiredEvent.class::isInstance).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT status FROM member_coupon", String.class)).isEqualTo("REMOVED");
        }

        @Test
        void 같은_영업일의_늦은_커밋은_다음_정기_실행에서_처리한다() {
            preparationJob.prepareCoupons();
            at(DATE.atTime(14, 0));
            expirationJob.expireCoupons();
            issue(1, 17, "ISSUED");
            at(DATE.atTime(15, 0));
            expirationJob.expireCoupons();
            issue(2, 18, "ISSUED");
            expirationJob.expireCoupons();
            assertThat(jdbc.queryForList("SELECT status FROM member_coupon ORDER BY member_coupon_id", String.class))
                    .containsExactly("EXPIRED", "EXPIRED");
            assertThat(funnel.received).filteredOn(CouponExpiredEvent.class::isInstance).hasSize(2);
        }

        @Test
        void 새벽_검증은_선행_작업_이력_없이_실행하고_불일치를_자동_보정하지_않는다() {
            preparationJob.prepareCoupons();
            issue(1, 17, "USED");
            jdbc.update("UPDATE coupon_daily_limit SET issued_count=0 WHERE member_id=17");
            at(DATE.plusDays(1).atTime(2, 0));
            consistencyJob.verifyCoupons();
            assertThat(jdbc.queryForObject("SELECT processed_count FROM batch_execution_log WHERE job_name='COUPON_VERIFY'", Integer.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_daily_limit", Integer.class)).isZero();
            assertThat(count("coupon_usage_history")).isZero();
        }

        @Test
        void 삭제된_쿠폰은_재고_대조에_포함하고_일일_한도에서_제외한다() {
            preparationJob.prepareCoupons();
            issue(1, 17, "REMOVED");
            at(DATE.plusDays(1).atTime(2, 0));
            consistencyJob.verifyCoupons();
            assertThat(jdbc.queryForObject("SELECT processed_count FROM batch_execution_log WHERE job_name='COUPON_VERIFY'", Integer.class))
                    .isZero();
        }

        @Test
        void 재고_저장_뒤_실패하면_회차도_함께_롤백한다() {
            executions.claim("COUPON_PREPARE", DATE);
            doAnswer(call -> {
                call.callRealMethod();
                throw new DataAccessResourceFailureException("재고 저장 뒤 실패");
            }).when(batches).prepareInventory(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
            assertThatThrownBy(() -> preparation.prepare(DATE, 1)).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(count("coupon_event")).isZero();
            assertThat(count("coupon_inventory")).isZero();
        }

        @Test
        void 실행_세대를_잃으면_회차를_만들지_못한다() {
            executions.claim("COUPON_PREPARE", DATE);
            jdbc.update("UPDATE batch_execution_log SET attempt_no=2 WHERE job_name='COUPON_PREPARE'");
            assertThatThrownBy(() -> preparation.prepare(DATE, 1)).isInstanceOf(IllegalStateException.class);
            assertThat(count("coupon_event")).isZero();
        }

        @Test
        void 최초_실행도_두번_재시도하고_실패한_회차의_재고는_중복되지_않는다() {
            java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                call.callRealMethod();
                if (failures.getAndIncrement() < 2) {
                    throw new IllegalStateException("재고 생성 중 실패");
                }
                return null;
            }).when(batches).prepareInventory(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());

            preparationJob.prepareCoupons();

            assertThat(count("coupon_event")).isEqualTo(1);
            assertThat(count("coupon_inventory")).isEqualTo(3);
            assertThat(jdbc.queryForMap("SELECT status, retry_count FROM batch_execution_log WHERE job_name='COUPON_PREPARE'"))
                    .containsEntry("status", "SUCCESS").containsEntry("retry_count", 2);
        }

        @Test
        void 두번의_재시도도_실패하면_FAILED로_남기고_스케줄_호출로_다시_실행하지_않는다() {
            doThrow(new IllegalStateException("계속 실패"))
                    .when(batches).prepareInventory(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());

            preparationJob.prepareCoupons();
            preparationJob.prepareCoupons();

            assertThat(count("coupon_event")).isZero();
            assertThat(count("coupon_inventory")).isZero();
            assertThat(jdbc.queryForMap("SELECT status, retry_count FROM batch_execution_log WHERE job_name='COUPON_PREPARE'"))
                    .containsEntry("status", "FAILED").containsEntry("retry_count", 2);
            verify(batches, times(3)).prepareInventory(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
        }

        @Test
        void 만료_재시도가_소진돼도_다음_주기에_남은_쿠폰을_다시_처리한다() {
            prepareExpiringCoupon();
            expirationJob.expireCoupons();
            issue(2, 18, "ISSUED");
            var failures = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                if (failures.getAndIncrement() < 4) {
                    throw new IllegalStateException("쿠폰 만료 중 실패");
                }
                return call.callRealMethod();
            }).when(batches).expiringIds(any(), any(), anyLong());

            expirationJob.expireCoupons();

            assertThat(jdbc.queryForMap("SELECT status, retry_count, attempt_no FROM batch_execution_log WHERE job_name='COUPON_EXPIRE'"))
                    .containsEntry("status", "FAILED").containsEntry("retry_count", 2).containsEntry("attempt_no", 2);
            assertThat(jdbc.queryForList("SELECT status FROM member_coupon ORDER BY member_coupon_id", String.class))
                    .containsExactly("EXPIRED", "ISSUED");

            at(DATE.atTime(15, 5));
            expirationJob.expireCoupons();

            assertThat(jdbc.queryForMap("SELECT status, retry_count, attempt_no, processed_count FROM batch_execution_log WHERE job_name='COUPON_EXPIRE'"))
                    .containsEntry("status", "SUCCESS").containsEntry("retry_count", 1)
                    .containsEntry("attempt_no", 3).containsEntry("processed_count", 1);
            assertThat(jdbc.queryForList("SELECT status FROM member_coupon ORDER BY member_coupon_id", String.class))
                    .containsExactly("EXPIRED", "EXPIRED");
            assertThat(funnel.received).filteredOn(CouponExpiredEvent.class::isInstance).hasSize(2);
        }

        @Test
        void 공통_복구_경로에서도_재시도_횟수가_늘어나지_않는다() {
            executions.claim("COUPON_PREPARE", DATE);
            doThrow(new DataAccessResourceFailureException("계속 실패"))
                    .when(batches).prepareInventory(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());

            batchJobRunner.resume("COUPON_PREPARE", DATE, 1);

            assertThat(count("coupon_event")).isZero();
            assertThat(jdbc.queryForMap("SELECT status, retry_count FROM batch_execution_log WHERE job_name='COUPON_PREPARE'"))
                    .containsEntry("status", "FAILED").containsEntry("retry_count", 2);
            verify(batches, times(3)).prepareInventory(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
        }

        @Test
        void 천건을_넘는_재고를_적재하고_쿠폰_만료의_다음_페이지도_빠짐없이_처리한다() {
            prepareLargeEvent();
            issueAll("ISSUED");
            at(DATE.atTime(15, 0));

            expirationJob.expireCoupons();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_coupon WHERE status='EXPIRED'", Integer.class))
                    .isEqualTo(1001);
            assertThat(jdbc.queryForObject("SELECT processed_count FROM batch_execution_log WHERE job_name='COUPON_EXPIRE'", Integer.class))
                    .isEqualTo(1001);
            assertThat(funnel.received).filteredOn(CouponExpiredEvent.class::isInstance).hasSize(1001);
        }

        @Test
        void 천건을_넘는_불일치도_다음_페이지까지_검증한다() {
            prepareLargeEvent();
            issueAll("USED");
            at(DATE.plusDays(1).atTime(2, 0));

            consistencyJob.verifyCoupons();

            assertThat(jdbc.queryForObject("SELECT processed_count FROM batch_execution_log WHERE job_name='COUPON_VERIFY'", Integer.class))
                    .isEqualTo(1001);
        }

        @Test
        void 재고만_있고_발급한_회원_쿠폰이_없으면_만료_배치를_시작하지_않는다() {
            preparationJob.prepareCoupons();
            at(DATE.atTime(15, 0));

            expirationJob.expireCoupons();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_execution_log WHERE job_name='COUPON_EXPIRE'", Integer.class))
                    .isZero();
            assertThat(count("member_coupon")).isZero();
        }

        @Test
        void 정기_실행은_종료_시각이_지난_미사용_회원_쿠폰만_만료한다() {
            preparationJob.prepareCoupons();
            issue(1, 17, "ISSUED");
            issue(2, 18, "USED");
            issue(3, 19, "REMOVED");
            at(DATE.atTime(14, 59, 59));
            expirationJob.expireCoupons();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM batch_execution_log WHERE job_name='COUPON_EXPIRE'", Integer.class))
                    .isZero();
            at(DATE.atTime(15, 0));

            expirationJob.expireCoupons();

            assertThat(jdbc.queryForList("SELECT status FROM member_coupon ORDER BY member_coupon_id", String.class))
                    .containsExactly("EXPIRED", "USED", "REMOVED");
            assertThat(funnel.received).filteredOn(CouponExpiredEvent.class::isInstance).hasSize(1);
        }

        @Test
        void 서버_중단으로_전날에_남은_미사용_쿠폰도_정기_실행에서_만료한다() {
            preparationJob.prepareCoupons();
            issue(1, 17, "ISSUED");
            at(DATE.plusDays(1).atTime(0, 5));

            expirationJob.expireCoupons();

            assertThat(jdbc.queryForObject("SELECT status FROM member_coupon", String.class)).isEqualTo("EXPIRED");
            assertThat(jdbc.queryForObject("SELECT business_date FROM batch_execution_log WHERE job_name='COUPON_EXPIRE'", LocalDate.class))
                    .isEqualTo(DATE);
        }

        @Test
        void 배치가_먼저_만료한_쿠폰은_목록과_상세에서_다시_만료하지_않는다() {
            prepareExpiringCoupon();

            expirationJob.expireCoupons();

            assertThat(couponLookUpService.getDetail(17L, 1L).state()).isEqualTo(MemberCouponStatus.EXPIRED);
            assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst().state())
                    .isEqualTo(MemberCouponStatus.EXPIRED);
            assertExpiredOnce();
        }

        @Test
        void 상세_조회가_쿠폰을_잠그면_만료_배치는_커밋을_기다린다() throws Exception {
            assertBatchWaitsForLookup(() -> couponLookUpService.getDetail(17L, 1L));
        }

        @Test
        void 목록_조회가_쿠폰을_잠그면_만료_배치는_커밋을_기다린다() throws Exception {
            assertBatchWaitsForLookup(() -> couponLookUpService.getCoupons(17L, null, 20, null));
        }

        @Test
        void 배치가_쿠폰을_잠그면_상세_조회는_만료_커밋을_기다린다() throws Exception {
            assertLookupWaitsForBatch(() -> {
                assertThat(couponLookUpService.getDetail(17L, 1L).state()).isEqualTo(MemberCouponStatus.EXPIRED);
            });
        }

        @Test
        void 배치가_쿠폰을_잠그면_목록_조회는_만료_커밋을_기다린다() throws Exception {
            assertLookupWaitsForBatch(() -> {
                assertThat(couponLookUpService.getCoupons(17L, null, 20, null).items().getFirst().state())
                        .isEqualTo(MemberCouponStatus.EXPIRED);
            });
        }

        private void prepareExpiringCoupon() {
            preparationJob.prepareCoupons();
            issue(1, 17, "ISSUED");
            at(DATE.atTime(15, 0));
        }

        private void assertBatchWaitsForLookup(Runnable lookup) throws Exception {
            prepareExpiringCoupon();
            var couponBefore = jdbc.queryForMap("SELECT * FROM member_coupon");
            var inventoryBefore = jdbc.queryForList("SELECT * FROM coupon_inventory");
            var limitBefore = jdbc.queryForList("SELECT * FROM coupon_daily_limit");
            CountDownLatch batchAtCouponLock = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<?> pending = new TransactionTemplate(transactionManager).execute(status -> {
                    lookup.run();
                    Future<?> batch = executor.submit(() -> SqlCapture.observeCouponLocks(
                            batchAtCouponLock::countDown, expirationJob::expireCoupons));
                    assertWaiting(batch, batchAtCouponLock);
                    return batch;
                });
                pending.get(10, TimeUnit.SECONDS);
            }
            assertThat(jdbc.queryForMap("SELECT * FROM member_coupon"))
                    .usingRecursiveComparison().ignoringFields("status", "updated_at").isEqualTo(couponBefore);
            assertThat(jdbc.queryForList("SELECT * FROM coupon_inventory")).usingRecursiveComparison().isEqualTo(inventoryBefore);
            assertThat(jdbc.queryForList("SELECT * FROM coupon_daily_limit")).isEqualTo(limitBefore);
            assertExpiredOnce();
        }

        private void assertLookupWaitsForBatch(Runnable lookup) throws Exception {
            prepareExpiringCoupon();
            executions.claim("COUPON_EXPIRE", DATE);
            CountDownLatch lookupAtCouponLock = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<?> pending = new TransactionTemplate(transactionManager).execute(status -> {
                    assertThat(expirationJob.runCounting(DATE, 1)).isEqualTo(1);
                    Future<?> query = executor.submit(() -> SqlCapture.observeCouponLocks(
                            lookupAtCouponLock::countDown, lookup));
                    assertWaiting(query, lookupAtCouponLock);
                    return query;
                });
                pending.get(10, TimeUnit.SECONDS);
            }
            assertExpiredOnce();
        }

        private void assertWaiting(Future<?> pending, CountDownLatch atLock) {
            try {
                assertThat(atLock.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        }

        private void assertExpiredOnce() {
            assertThat(jdbc.queryForObject("SELECT status FROM member_coupon", String.class)).isEqualTo("EXPIRED");
            assertThat(funnel.received).filteredOn(CouponExpiredEvent.class::isInstance).hasSize(1);
        }

        private void prepareLargeEvent() {
            CampaignInfo campaign = campaign(1001);
            when(campaignQueryService.findServableOn(any())).thenReturn(List.of(campaign));
            when(campaignQueryService.findByIdForUpdate(anyLong())).thenReturn(Optional.of(campaign));
            preparationJob.prepareCoupons();
            assertThat(count("coupon_inventory")).isEqualTo(1001);
            assertThat(jdbc.queryForObject("SELECT MAX(sequence_no) FROM coupon_inventory", Integer.class)).isEqualTo(1001);
        }

        private void issueAll(String state) {
            jdbc.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                        status, usable_start_at, usable_end_at, created_at, updated_at)
                    SELECT sequence_no, coupon_event_id, sequence_no, coupon_code, ?, usable_start_at,
                           usable_end_at, ?, ? FROM coupon_inventory
                    """, state, DATE.atTime(12, 0), DATE.atTime(12, 0));
            jdbc.update("UPDATE coupon_inventory SET status='ISSUED'");
            jdbc.update("""
                    INSERT INTO coupon_daily_limit (member_id, issued_count, business_date, created_at, updated_at)
                    SELECT member_id, 1, ?, ?, ? FROM member_coupon
                    """, DATE, DATE.atTime(12, 0), DATE.atTime(12, 0));
        }

        private void issue(long sequence, long memberId, String state) {
            jdbc.update("""
                    INSERT INTO member_coupon (member_coupon_id, coupon_event_id, member_id, coupon_code,
                        status, usable_start_at, usable_end_at, created_at, updated_at)
                    SELECT ?, coupon_event_id, ?, coupon_code, ?, usable_start_at, usable_end_at, ?, ?
                      FROM coupon_inventory WHERE sequence_no = ?
                    """, sequence, memberId, state, DATE.atTime(12, 0), DATE.atTime(12, 0), sequence);
            jdbc.update("UPDATE coupon_inventory SET status='ISSUED' WHERE sequence_no=?", sequence);
            jdbc.update("""
                    INSERT INTO coupon_daily_limit (member_id, issued_count, business_date, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, memberId, state.equals("REMOVED") ? 0 : 1, DATE, DATE.atTime(12, 0), DATE.atTime(12, 0));
        }

        private int count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        private void at(LocalDateTime time) {
            Instant instant = time.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
            doReturn(instant).when(clock).instant();
            doReturn(java.time.ZoneId.of("Asia/Seoul")).when(clock).getZone();
            doReturn(Clock.fixed(instant, java.time.ZoneId.of("Asia/Seoul"))).when(clock).withZone(any());
        }

        private CampaignInfo campaign() {
            return campaign(3);
        }

        private CampaignInfo campaign(int quantity) {
            return new CampaignInfo(91L, 1L, 10L, CampaignStatus.ACTIVE, quantity, LocalTime.of(11, 0), LocalTime.of(15, 0),
                    DATE.minusDays(1), DATE.plusDays(1), DiscountTargetType.MENU, DiscountType.PERCENT, 15,
                    100L, 1000, com.launchcatch.campaign.contract.TargetGender.ALL, List.of(),
                    DATE.minusDays(1).atStartOfDay(), null, DATE.atStartOfDay(), null, 10000L);
        }

        @AfterEach
        void cleanBatchData() {
            jdbc.update("DELETE FROM coupon_usage_history");
            jdbc.update("DELETE FROM member_coupon");
            jdbc.update("DELETE FROM coupon_daily_limit");
            jdbc.update("DELETE FROM coupon_inventory");
            jdbc.update("DELETE FROM coupon_event");
            jdbc.update("DELETE FROM batch_execution_log");
        }
    }
}
