package com.launchcatch.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.service.CouponCommandService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberStatus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CouponIssuanceIntegrationTest extends AbstractCouponIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired CouponCommandService service;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager entityManager;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private CouponIssuanceFixture fixture;

    @BeforeEach
    void setUp() {
        time.set(Instant.parse("2026-10-10T03:00:00Z"));
        when(clock.withZone(ClockConfig.ZONE)).thenAnswer(invocation -> Clock.fixed(time.get(), ClockConfig.ZONE));
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenAnswer(invocation -> time.get());
        doAnswer(invocation -> Optional.of(new MemberInfo(
                invocation.getArgument(0), "회원", MemberStatus.ACTIVE, false, false, false, null, null, null, null)))
                .when(members).findById(any());
        fixture = new CouponIssuanceFixture(jdbc, TODAY);
        fixture.event(81L, 3);
    }

    @AfterEach
    void cleanUp() {
        fixture.clear();
    }

    @Test
    void HTTP_발급은_201과_저장된_한국_시각을_반환한다() throws Exception {
        mvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
                        .contentType("application/json").content("{\"eventId\":81}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.memberCouponId").isNumber())
                .andExpect(jsonPath("$.data.issueTime").value("2026-10-10T12:00:00+09:00"));
        assertConsistent(1);
        assertThat(jdbc.queryForObject("SELECT sequence_no FROM coupon_inventory WHERE status='ISSUED'", Integer.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"eventId\":null}", "{\"eventId\":0}", "{\"eventId\":-1}",
            "{\"eventId\":81.2}", "{\"eventId\":\"81\"}", "{\"eventId\":true}",
            "{\"eventId\":9223372036854775808}"})
    void 잘못된_요청은_발급하지_않는다(String body) throws Exception {
        mvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
                        .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        assertConsistent(0);
    }

    @Test
    void 인증과_회원_역할이_필요하다() throws Exception {
        mvc.perform(post("/v1/coupons").contentType("application/json").content("{\"eventId\":81}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.OWNER)))
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
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT coupon_code) FROM member_coupon", Integer.class))
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
            fixture.event(id, 1);
        }
        List<String> results = concurrently(IntStream.rangeClosed(81, 85).mapToObj(i -> new long[] {17L, i}).toList());
        assertThat(results.stream().filter("CREATED"::equals)).hasSize(3);
        assertThat(results.stream().filter("COUPON-007"::equals)).hasSize(2);
        assertConsistent(3);
    }

    @Test
    void 삭제한_이벤트에서_다시_발급할_수_없고_다른_이벤트는_발급할_수_있다() {
        var issued = service.issue(17L, 81L);
        service.deleteCoupon(17L, issued.memberCouponId());
        assertError(17L, 81L, CouponErrorCode.EVENT_ALREADY_ISSUED);
        fixture.event(82L, 1);
        service.issue(17L, 82L);
        assertThat(jdbc.queryForObject("SELECT SUM(issued_count) FROM coupon_daily_limit", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_inventory WHERE status='ISSUED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void 삭제와_다른_이벤트_발급이_겹쳐도_카운터_변경을_잃지_않는다() throws Exception {
        var first = service.issue(17L, 81L);
        fixture.event(82L, 1);
        fixture.event(83L, 1);
        fixture.event(84L, 1);
        service.issue(17L, 82L);
        service.issue(17L, 83L);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> deletion = executor.submit(() -> {
                await(start);
                service.deleteCoupon(17L, first.memberCouponId());
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
            assertThat(jdbc.queryForObject("SELECT SUM(issued_count) FROM coupon_daily_limit", Integer.class))
                    .isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_coupon WHERE status='ISSUED'", Integer.class))
                    .isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_inventory WHERE status='ISSUED'", Integer.class))
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
        assertThatThrownBy(() -> service.issue(17L, 81L)).isSameAs(failure);
        assertConsistent(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_daily_limit", Integer.class)).isZero();
    }

    @Test
    void DB_조회가_실패하면_공통_서버_오류를_반환하고_롤백한다() throws Exception {
        doThrow(new QueryTimeoutException("DB 조회 실패")).when(events).findByIdForUpdate(81L);
        mvc.perform(post("/v1/coupons").with(user(new CustomUserDetails(17L, Role.MEMBER)))
                        .contentType("application/json").content("{\"eventId\":81}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON-001"))
                .andExpect(header().doesNotExist("Retry-After"));
        assertConsistent(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_daily_limit", Integer.class)).isZero();
    }

    @Test
    void 잠금_대기_중_발급_시간이_끝나면_발급하지_않는다() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT coupon_event_id FROM coupon_event WHERE coupon_event_id=81 FOR UPDATE", Long.class);
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
            service.issue(memberId, eventId);
            return "CREATED";
        } catch (CouponException exception) {
            return exception.getErrorCode().getCode();
        }
    }

    private void assertError(long memberId, long eventId, CouponErrorCode code) {
        assertThatThrownBy(() -> service.issue(memberId, eventId)).isInstanceOfSatisfying(CouponException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    private void assertConsistent(int expected) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_coupon", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_inventory WHERE status='ISSUED'", Integer.class))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(issued_count),0) FROM coupon_daily_limit", Integer.class))
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
        return Integer.parseInt(jdbc.queryForMap("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_current_waits'")
                .get("Value").toString());
    }
}
