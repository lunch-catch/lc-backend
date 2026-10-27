package com.lunchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.lunchcatch.coupon.CouponTestProperties;
import com.lunchcatch.coupon.config.CouponProperties;
import com.lunchcatch.coupon.dto.CouponIssueResponse;
import com.lunchcatch.coupon.CouponQrImageEncoder;
import com.lunchcatch.coupon.config.CouponIssuanceMetrics;
import com.lunchcatch.coupon.dto.CouponQrResponse;
import com.lunchcatch.coupon.entity.CouponDailyLimit;
import com.lunchcatch.coupon.entity.MemberCoupon;
import com.lunchcatch.coupon.entity.MemberCouponStatus;
import com.lunchcatch.coupon.exception.CouponErrorCode;
import com.lunchcatch.coupon.exception.CouponException;
import com.lunchcatch.coupon.repository.CouponDailyLimitRepository;
import com.lunchcatch.coupon.repository.MemberCouponRepository;
import com.lunchcatch.coupon.model.CouponIssuanceResult;
import com.lunchcatch.coupon.model.CouponIssuanceRequest;
import com.lunchcatch.member.contract.MemberInfo;
import com.lunchcatch.member.contract.MemberQueryService;
import com.lunchcatch.member.contract.MemberStatus;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("회원 쿠폰 변경 서비스")
class CouponCommandServiceTest {

    private static final Long MEMBER_ID = 17L;
    private static final Long COUPON_ID = 1001L;
    private static final LocalDate ISSUE_DATE = LocalDate.of(2026, 10, 10);
    private static final LocalDateTime QR_EXPIRES_AT = LocalDateTime.of(2026, 10, 11, 12, 1);
    private static final UUID QR_TOKEN = UUID.randomUUID();

    @Mock MemberCouponRepository memberCouponRepository;
    @Mock CouponDailyLimitRepository couponDailyLimitRepository;
    @Mock CouponSoldOutNotificationService soldOutNotifications;
    @Mock CouponSequenceService sequenceService;
    @Mock CouponSequenceRecoveryService sequenceRecoveryService;
    @Mock CouponIssueBatcher batcher;
    @Mock CouponIssuanceMetrics issuanceMetrics;
    @Spy CouponProperties properties = CouponTestProperties.defaults();
    @InjectMocks CouponCommandService service;

    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC));
        logger = (Logger) LoggerFactory.getLogger(CouponCommandService.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanUpLogs() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        logs.stop();
    }

    @Test
    void 비동기_접수_상한을_넘으면_즉시_혼잡으로_거절하고_집계한다() {
        ReflectionTestUtils.setField(service, "properties", CouponTestProperties.with(java.util.Map.of("coupon.issue.batch.queue-capacity", 0)));

        assertThatThrownBy(() -> service.issueAsync(MEMBER_ID, 81L))
                .isInstanceOfSatisfying(CouponException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY);
                    verify(issuanceMetrics).record(error);
                });

        verifyNoInteractions(batcher, soldOutNotifications);
    }

    @Test
    void 비동기_접수_상한을_넘어도_확정_소진을_우선_응답한다() {
        ReflectionTestUtils.setField(service, "properties", CouponTestProperties.with(java.util.Map.of("coupon.issue.batch.queue-capacity", 0)));
        when(sequenceService.isConfirmedSoldOut(81L)).thenReturn(true);

        assertThatThrownBy(() -> service.issueAsync(MEMBER_ID, 81L))
                .isInstanceOfSatisfying(CouponException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED);
                    verify(issuanceMetrics).record(error);
                });

        verify(soldOutNotifications).request(81L);
        verifyNoInteractions(batcher);
    }

    @Test
    void 비동기_복구가_실패하면_재접수하지_않고_원래_오류를_반환한다() {
        var rejection = CouponException.recoveryRequired(81L);
        rejection.confirmIssuanceNotCommitted();
        when(batcher.submit(any(), anyLong())).thenReturn(CompletableFuture.failedFuture(rejection));
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        when(sequenceRecoveryService.recover(eq(81L), anyLong())).thenReturn(false);
        try {
            var result = service.issueAsync(MEMBER_ID, 81L);

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).hasCause(rejection);
            verify(batcher).submit(any(), anyLong());
            verify(issuanceMetrics).record(any(Throwable.class));
            org.mockito.Mockito.verifyNoMoreInteractions(batcher, issuanceMetrics);
        } finally {
            service.stopIssuanceRecovery();
        }
    }

    @Test
    void 비동기_접수는_발급_완료_전에는_응답을_확정하지_않는다() throws Exception {
        var pending = new CompletableFuture<CouponIssuanceResult>();
        var response = new CouponIssueResponse(COUPON_ID, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        when(batcher.submit(any(), anyLong())).thenReturn(pending);

        var result = service.issueAsync(MEMBER_ID, 81L);

        assertThat(result).isNotDone();
        verifyNoInteractions(issuanceMetrics);
        pending.complete(new CouponIssuanceResult(response, false));
        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo(response);
        verify(issuanceMetrics).record(null);
    }

    @Test
    void 확정_소진은_배처에_접수하지_않고_통보와_결과를_한번만_기록한다() {
        var registry = new SimpleMeterRegistry();
        try {
            ReflectionTestUtils.setField(service, "issuanceMetrics", new CouponIssuanceMetrics(registry));
            when(sequenceService.isConfirmedSoldOut(81L)).thenReturn(true);

            var result = service.issueAsync(MEMBER_ID, 81L);

            assertThatThrownBy(result::join).hasCauseInstanceOf(CouponException.class);
            assertThat(registry.get("coupon.issue.results").tag("result", "COUPON-006").counter().count())
                    .isEqualTo(1);
            assertThat(registry.get("coupon.issue.results").tag("result", "SUCCESS").counter().count()).isZero();
            verify(soldOutNotifications).request(81L);
            verifyNoInteractions(batcher);
        } finally {
            registry.close();
        }
    }

    @Test
    void 복구와_재접수는_원래_추적ID를_유지하고_최종_결과만_집계한다() throws Exception {
        var rejection = CouponException.recoveryRequired(81L);
        rejection.confirmIssuanceNotCommitted();
        var response = new CouponIssueResponse(COUPON_ID, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        var recoveryTrace = new AtomicReference<String>();
        var retryTrace = new AtomicReference<String>();
        when(batcher.submit(any(), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(rejection))
                .thenAnswer(invocation -> {
                    retryTrace.set(MDC.get("traceId"));
                    return CompletableFuture.completedFuture(new CouponIssuanceResult(response, false));
                });
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        when(sequenceRecoveryService.recover(eq(81L), anyLong())).thenAnswer(invocation -> {
            recoveryTrace.set(MDC.get("traceId"));
            return true;
        });
        try {
            MDC.put("traceId", "request-trace");

            assertThat(service.issueAsync(MEMBER_ID, 81L).get(2, TimeUnit.SECONDS)).isEqualTo(response);

            assertThat(recoveryTrace.get()).isEqualTo("request-trace");
            assertThat(retryTrace.get()).isEqualTo("request-trace");
            assertThat(MDC.get("traceId")).isEqualTo("request-trace");
            verify(issuanceMetrics).record(null);
            org.mockito.Mockito.verifyNoMoreInteractions(issuanceMetrics);
        } finally {
            MDC.clear();
            service.stopIssuanceRecovery();
        }
    }

    @Test
    void 종료_대기가_중단돼도_복구중이거나_대기중인_요청을_모두_끝낸다() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var rejection = new CouponException(CouponErrorCode.ISSUANCE_BUSY);
        when(batcher.submit(any(), anyLong())).thenReturn(CompletableFuture.failedFuture(rejection));
        when(sequenceService.needsRecovery(anyLong())).thenReturn(true);
        when(sequenceRecoveryService.recover(anyLong(), anyLong())).thenAnswer(invocation -> {
            started.countDown();
            release.await();
            return false;
        });

        var running = service.issueAsync(MEMBER_ID, 81L);
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var queued = service.issueAsync(MEMBER_ID, 82L);
            Thread.currentThread().interrupt();
            service.stopIssuanceRecovery();

            assertThat(running).isCompletedExceptionally();
            assertThat(queued).isCompletedExceptionally();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            release.countDown();
            service.stopIssuanceRecovery();
        }
    }

    @ParameterizedTest
    @EnumSource(value = MemberCouponStatus.class, names = {"ISSUED", "EXPIRED"})
    void 미사용과_만료_쿠폰을_삭제하고_QR_무효화와_발급_횟수_차감을_반영한다(MemberCouponStatus status) {
        MemberCoupon coupon = coupon(status);
        CouponDailyLimit dailyLimit = dailyLimit(3);
        when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.of(coupon));
        when(couponDailyLimitRepository.findByMemberIdAndBusinessDate(MEMBER_ID, ISSUE_DATE))
                .thenReturn(Optional.of(dailyLimit));

        service.deleteCoupon(MEMBER_ID, COUPON_ID);

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.REMOVED);
        assertThat(coupon.getQrVersion()).isEqualTo(4);
        assertThat(coupon.getQrToken()).isNull();
        assertThat(coupon.getQrExpiresAt()).isNull();
        assertThat(dailyLimit.getIssuedCount()).isEqualTo(2);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 이미_삭제된_본인_쿠폰을_다시_삭제해도_추가로_변경하지_않는다() {
        MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
        CouponDailyLimit dailyLimit = dailyLimit(2);
        when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.of(coupon));
        when(couponDailyLimitRepository.findByMemberIdAndBusinessDate(MEMBER_ID, ISSUE_DATE))
                .thenReturn(Optional.of(dailyLimit));
        service.deleteCoupon(MEMBER_ID, COUPON_ID);

        service.deleteCoupon(MEMBER_ID, COUPON_ID);

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.REMOVED);
        assertThat(coupon.getQrVersion()).isEqualTo(4);
        assertThat(coupon.getQrToken()).isNull();
        assertThat(coupon.getQrExpiresAt()).isNull();
        assertThat(dailyLimit.getIssuedCount()).isEqualTo(1);
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "0, 0"})
    void 발급_횟수는_0보다_작아지지_않는다(int issuedCount, int expected) {
        MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
        CouponDailyLimit dailyLimit = dailyLimit(issuedCount);
        when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.of(coupon));
        when(couponDailyLimitRepository.findByMemberIdAndBusinessDate(MEMBER_ID, ISSUE_DATE))
                .thenReturn(Optional.of(dailyLimit));

        service.deleteCoupon(MEMBER_ID, COUPON_ID);

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.REMOVED);
        assertThat(dailyLimit.getIssuedCount()).isEqualTo(expected);
    }

    @Test
    void 발급_날짜의_횟수_기록이_없어도_쿠폰은_삭제한다() {
        MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
        when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.of(coupon));
        when(couponDailyLimitRepository.findByMemberIdAndBusinessDate(MEMBER_ID, ISSUE_DATE))
                .thenReturn(Optional.empty());

        service.deleteCoupon(MEMBER_ID, COUPON_ID);

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.REMOVED);
        assertThat(coupon.getQrToken()).isNull();
    }

    @Test
    void 사용_완료_쿠폰은_상태와_QR을_변경하지_않고_삭제를_거절한다() {
        MemberCoupon coupon = coupon(MemberCouponStatus.USED);
        when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.of(coupon));

        assertError(CouponErrorCode.COUPON_ALREADY_USED);

        assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.USED);
        assertThat(coupon.getQrVersion()).isEqualTo(3);
        assertThat(coupon.getQrToken()).isEqualTo(QR_TOKEN);
        assertThat(coupon.getQrExpiresAt()).isEqualTo(QR_EXPIRES_AT);
        assertDeletionFailure("COUPON-012");
    }

    @Test
    void 본인_쿠폰_범위에_없으면_존재_여부와_관계없이_접근을_거절한다() {
        when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.empty());

        assertError(CouponErrorCode.COUPON_ACCESS_DENIED);
        assertDeletionFailure("COUPON-011");
    }

    @Test
    void 쿠폰_조회_실패를_접근_거절이나_삭제_성공으로_바꾸지_않는다() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.deleteCoupon(MEMBER_ID, COUPON_ID)).isSameAs(failure);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 조회_실패를_그대로_전달하고_삭제_트랜잭션을_롤백한다() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID))
                .thenReturn(Optional.of(coupon(MemberCouponStatus.ISSUED)));
        when(couponDailyLimitRepository.findByMemberIdAndBusinessDate(MEMBER_ID, ISSUE_DATE)).thenThrow(failure);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        var transaction = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transaction);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service);
        factory.addAdvice(interceptor);
        CouponCommandService transactionalService = (CouponCommandService) factory.getProxy();

        assertThatThrownBy(() -> transactionalService.deleteCoupon(MEMBER_ID, COUPON_ID)).isSameAs(failure);

        verify(transactionManager).rollback(transaction);
        assertThat(logs.list).isEmpty();
    }

    private void assertDeletionFailure(String reason) {
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "event=COUPON_DELETE_FAILED memberId=17 memberCouponId=1001 errorCode=" + reason);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void assertError(CouponErrorCode errorCode) {
        assertThatThrownBy(() -> service.deleteCoupon(MEMBER_ID, COUPON_ID))
                .isInstanceOfSatisfying(CouponException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    // 삭제 요청에 사용할 쿠폰 상태와 기존 QR 정보를 구성한다.
    private MemberCoupon coupon(MemberCouponStatus status) {
        MemberCoupon coupon = BeanUtils.instantiateClass(MemberCoupon.class);
        ReflectionTestUtils.setField(coupon, "status", status);
        ReflectionTestUtils.setField(coupon, "qrVersion", 3);
        ReflectionTestUtils.setField(coupon, "qrToken", QR_TOKEN);
        ReflectionTestUtils.setField(coupon, "qrExpiresAt", QR_EXPIRES_AT);
        ReflectionTestUtils.setField(coupon, "createdAt", ISSUE_DATE.atTime(12, 0));
        return coupon;
    }

    private CouponDailyLimit dailyLimit(int issuedCount) {
        CouponDailyLimit dailyLimit = BeanUtils.instantiateClass(CouponDailyLimit.class);
        ReflectionTestUtils.setField(dailyLimit, "issuedCount", issuedCount);
        return dailyLimit;
    }

    @Nested
    @DisplayName("QR 생성과 갱신")
    class GenerateQr {

        private static final Long MEMBER_ID = 17L;
        private static final Long COUPON_ID = 1001L;
        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);
        private static final UUID OLD_TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000004");

        @Mock MemberCouponRepository repository;
        @Mock MemberQueryService memberQuery;
        private CouponCommandService service;

        @BeforeEach
        void setUp() {
            service = new CouponCommandService(CouponTestProperties.defaults(), repository, couponDailyLimitRepository, memberQuery,
                    new CouponQrImageEncoder(CouponTestProperties.defaults()),
                    Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC), mock(CouponSoldOutNotificationService.class), mock(CouponSequenceService.class), mock(CouponSequenceRecoveryService.class),
                    mock(CouponIssueBatcher.class), mock(CouponIssuanceMetrics.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "f0a609fe-b881-4607-8179-cefca7c6d219",
                "07295f2a-465c-4c0c-80c5-d3b3e9b9eb5c",
                "c7ad4239-64e5-4536-9849-062b75f6527f",
                "b79a7c10-cb20-4260-a4d4-8524ab4c9984",
                "65ef8250-14e3-4518-9fc0-0aefad0c00c4"
        })
        void 위치_표시와_비슷한_무늬의_QR도_원래_토큰으로_읽는다(String tokenValue) throws Exception {
            UUID token = UUID.fromString(tokenValue);
            CouponQrResponse response = new CouponQrResponse(new CouponQrImageEncoder(CouponTestProperties.defaults()).encode(token),
                    OffsetDateTime.parse("2026-10-10T12:01:00+09:00"));

            assertThat(decode(response)).isEqualTo(tokenValue);
        }

        @Test
        void 첫_QR은_저장된_토큰의_PNG이며_한국_시간으로_60초_후_만료한다() throws Exception {
            MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
            ReflectionTestUtils.setField(coupon, "qrVersion", 0);
            ReflectionTestUtils.setField(coupon, "qrToken", null);
            ReflectionTestUtils.setField(coupon, "qrExpiresAt", null);
            ReflectionTestUtils.setField(coupon, "usableStartAt", NOW);
            ReflectionTestUtils.setField(coupon, "usableEndAt", NOW.plusSeconds(10));
            stub(coupon, MemberStatus.ACTIVE);

            CouponQrResponse response = service.generateQr(MEMBER_ID, COUPON_ID);

            assertThat(decode(response)).isEqualTo(coupon.getQrToken().toString());
            assertThat(coupon.getQrVersion()).isEqualTo(1);
            assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
            assertThat(coupon.getQrExpiresAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(response.expireTime()).isEqualTo(OffsetDateTime.parse("2026-10-10T12:01:00+09:00"));
        }

        @Test
        void 갱신하면_만료된_이전_토큰을_교체한다() throws Exception {
            MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
            ReflectionTestUtils.setField(coupon, "qrExpiresAt", NOW.minusSeconds(1));
            stub(coupon, MemberStatus.ACTIVE);

            CouponQrResponse response = service.generateQr(MEMBER_ID, COUPON_ID);

            assertThat(coupon.getQrToken()).isNotEqualTo(OLD_TOKEN);
            assertThat(decode(response)).isEqualTo(coupon.getQrToken().toString());
            assertThat(coupon.getQrVersion()).isEqualTo(4);
            assertThat(coupon.getQrExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        }

        @ParameterizedTest
        @EnumSource(value = MemberCouponStatus.class, names = {"EXPIRED", "REMOVED"})
        void 사용할_수_없는_쿠폰_상태는_QR을_변경하지_않고_거절한다(MemberCouponStatus status) {
            MemberCoupon coupon = coupon(status);
            stub(coupon, MemberStatus.ACTIVE);

            assertError(CouponErrorCode.COUPON_NOT_AVAILABLE);
            assertUnchanged(coupon);
        }

        @Test
        void 사용_완료_쿠폰은_QR을_변경하지_않고_사용_완료_오류로_거절한다() {
            MemberCoupon coupon = coupon(MemberCouponStatus.USED);
            stub(coupon, MemberStatus.ACTIVE);

            assertError(CouponErrorCode.COUPON_ALREADY_USED);
            assertUnchanged(coupon);
        }

        @Test
        void 사용_시작_전에는_QR을_변경하지_않고_거절한다() {
            MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
            ReflectionTestUtils.setField(coupon, "usableStartAt", NOW.plusNanos(1));
            stub(coupon, MemberStatus.ACTIVE);

            assertError(CouponErrorCode.COUPON_NOT_AVAILABLE);
            assertUnchanged(coupon);
        }

        @Test
        void 사용_종료_시각부터는_QR을_변경하지_않고_거절한다() {
            MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
            ReflectionTestUtils.setField(coupon, "usableEndAt", NOW);
            stub(coupon, MemberStatus.ACTIVE);

            assertError(CouponErrorCode.COUPON_NOT_AVAILABLE);
            assertUnchanged(coupon);
        }

        @Test
        void 정지된_회원은_QR을_변경하지_않고_거절한다() {
            MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
            stub(coupon, MemberStatus.SUSPENDED);

            assertError(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
            assertUnchanged(coupon);
        }

        @Test
        void 회원을_찾을_수_없어도_QR을_변경하지_않고_거절한다() {
            MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
            when(repository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.of(coupon));
            when(memberQuery.findById(MEMBER_ID)).thenReturn(Optional.empty());

            assertError(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
            assertUnchanged(coupon);
        }

        // QR 생성 요청이 지정한 쿠폰 오류로 거절되는지 검증한다.
        private void assertError(CouponErrorCode error) {
            assertThatThrownBy(() -> service.generateQr(MEMBER_ID, COUPON_ID))
                    .isInstanceOfSatisfying(CouponException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
            assertThat(logs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "event=COUPON_QR_GENERATION_FAILED memberId=17 memberCouponId=1001 errorCode=" + error.getCode());
                assertThat(event.getThrowableProxy()).isNull();
            });
        }

        // 거절된 요청이 기존 QR 토큰과 버전, 만료 시각을 변경하지 않았는지 검증한다.
        private void assertUnchanged(MemberCoupon coupon) {
            assertThat(coupon.getQrToken()).isEqualTo(OLD_TOKEN);
            assertThat(coupon.getQrVersion()).isEqualTo(3);
            assertThat(coupon.getQrExpiresAt()).isEqualTo(NOW.plusSeconds(30));
        }

        // 본인 쿠폰과 지정한 상태의 회원 정보가 조회되도록 설정한다.
        private void stub(MemberCoupon coupon, MemberStatus status) {
            when(repository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.of(coupon));
            when(memberQuery.findById(MEMBER_ID)).thenReturn(Optional.of(member(status)));
        }

        // 지정한 현재 상태를 가진 쿠폰 소유 회원의 공개 계약 응답을 구성한다.
        private MemberInfo member(MemberStatus status) {
            return new MemberInfo(MEMBER_ID, "점심헌터", status, false, false, false, null, null, null, null);
        }

        // QR 생성에 사용할 회원 쿠폰의 상태와 기존 QR 정보를 구성한다.
        private MemberCoupon coupon(MemberCouponStatus status) {
            MemberCoupon coupon = BeanUtils.instantiateClass(MemberCoupon.class);
            ReflectionTestUtils.setField(coupon, "memberId", MEMBER_ID);
            ReflectionTestUtils.setField(coupon, "status", status);
            ReflectionTestUtils.setField(coupon, "usableStartAt", NOW.minusHours(1));
            ReflectionTestUtils.setField(coupon, "usableEndAt", NOW.plusHours(1));
            ReflectionTestUtils.setField(coupon, "qrVersion", 3);
            ReflectionTestUtils.setField(coupon, "qrToken", OLD_TOKEN);
            ReflectionTestUtils.setField(coupon, "qrExpiresAt", NOW.plusSeconds(30));
            return coupon;
        }

        // 응답의 PNG 형식을 검증하고 QR에 담긴 토큰을 읽는다.
        private String decode(CouponQrResponse response) throws Exception {
            byte[] png = Base64.getDecoder().decode(response.qrImageBytes());
            assertThat(png).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
            var image = ImageIO.read(new ByteArrayInputStream(png));
            // 직접 만든 PNG는 QR 위치를 추측하지 않고 원본 격자를 읽는다.
            return new QRCodeReader().decode(new BinaryBitmap(
                    new HybridBinarizer(new BufferedImageLuminanceSource(image))),
                    Map.of(DecodeHintType.PURE_BARCODE, true)).getText();
        }
    }


    @Test
    void 접수_상한에_닿아도_확정_소진을_먼저_반환한다() {
        ReflectionTestUtils.setField(service, "properties", CouponTestProperties.with(java.util.Map.of("coupon.issue.batch.queue-capacity", 0)));
        when(sequenceService.isConfirmedSoldOut(81L)).thenReturn(true);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED));

        verify(soldOutNotifications).request(81L);
        verifyNoInteractions(batcher, memberCouponRepository, couponDailyLimitRepository, sequenceRecoveryService);
    }

    @Test
    void 접수_상한에_닿고_소진이_아니면_혼잡으로_거절한다() {
        ReflectionTestUtils.setField(service, "properties", CouponTestProperties.with(java.util.Map.of("coupon.issue.batch.queue-capacity", 0)));

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));

        verifyNoInteractions(batcher, soldOutNotifications, sequenceRecoveryService);
    }

    @Test
    void 접수_상한에서_Redis_복구가_필요해도_요청_스레드는_복구를_시작하지_않는다() {
        ReflectionTestUtils.setField(service, "properties", CouponTestProperties.with(java.util.Map.of("coupon.issue.batch.queue-capacity", 0)));
        when(sequenceService.isConfirmedSoldOut(81L)).thenThrow(CouponException.recoveryRequired(81L));

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.ISSUANCE_BUSY));

        verifyNoInteractions(batcher, soldOutNotifications, sequenceRecoveryService);
    }

    @Test
    void Redis에서_소진이_확정되면_발급_대기열과_DB_작업을_사용하지_않는다() {
        when(sequenceService.isConfirmedSoldOut(81L)).thenReturn(true);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED));
        verifyNoInteractions(batcher, memberCouponRepository, couponDailyLimitRepository, sequenceRecoveryService);
        verify(soldOutNotifications).request(81L);
    }

    @Test
    void 사전_검사_뒤_소진돼도_발급_작업자가_돌려준_소진_거절을_유지한다() {
        when(sequenceService.isConfirmedSoldOut(81L)).thenReturn(false);
        CouponException failure = new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED);
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(soldOutNotifications).request(81L);
    }

    @Test
    void Redis_사전_검사를_복구한_뒤_소진이_확정되면_큐에_넣지_않는다() {
        CouponException failure = CouponException.recoveryRequired(81L);
        failure.confirmIssuanceNotCommitted();
        when(sequenceService.isConfirmedSoldOut(81L)).thenThrow(failure).thenReturn(true);
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        when(sequenceRecoveryService.recover(eq(81L), anyLong())).thenReturn(true);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isInstanceOfSatisfying(CouponException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.INVENTORY_EXHAUSTED));
        verifyNoInteractions(batcher);
        verify(soldOutNotifications).request(81L);
    }

    @Test
    void 마지막_재고를_발급한_요청은_DB_재확인과_광고_통보를_기다리지_않는다() {
        CouponIssueResponse response = new CouponIssueResponse(1001L, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenReturn(new CouponIssuanceResult(response, true));

        assertThat(service.issue(MEMBER_ID, 81L)).isEqualTo(response);
        verifyNoInteractions(soldOutNotifications);
    }

    @Test
    void 남은_재고가_있으면_소진을_통보하지_않는다() {
        CouponIssueResponse response = new CouponIssueResponse(1001L, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenReturn(new CouponIssuanceResult(response, false));
        assertThat(service.issue(MEMBER_ID, 81L)).isEqualTo(response);
        verifyNoInteractions(soldOutNotifications);
    }

    @Test
    void 소진_거절은_DB_재확인_없이_통보를_접수하고_원래_결과를_반환한다() {
        CouponException failure = new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED);
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(soldOutNotifications).request(81L);
    }

    @Test
    void 예약_전_실패나_확정된_롤백은_전체_큐를_재구축하지_않는다() {
        QueryTimeoutException failure = new QueryTimeoutException("connection unavailable");
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);
        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verifyNoInteractions(sequenceRecoveryService);
    }

    @Test
    void DB_처리_결과가_불확실해_차단됐으면_복구한_뒤_원래_오류를_반환한다() {
        QueryTimeoutException failure = new QueryTimeoutException("commit reply lost");
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(sequenceRecoveryService).recover(eq(81L), anyLong());
    }

    @Test
    void 다른_발급_거절에는_소진을_통보하지_않는다() {
        CouponException failure = new CouponException(CouponErrorCode.CAMPAIGN_NOT_WISHED);
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);
        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verifyNoInteractions(soldOutNotifications);
    }
    @Test
    void 차단을_복구한_요청은_실제_발급까지_이어간다() {
        CouponException blocked = CouponException.recoveryRequired(81L);
        blocked.confirmIssuanceNotCommitted();
        doThrow(blocked).doNothing().when(sequenceService).checkAdmission(81L);
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        when(sequenceRecoveryService.recover(eq(81L), anyLong())).thenReturn(true);
        CouponIssueResponse response = new CouponIssueResponse(1001L, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong()))
                .thenReturn(new CouponIssuanceResult(response, false));

        assertThat(service.issue(MEMBER_ID, 81L)).isEqualTo(response);
    }

    @Test
    void 복구_후_재접수는_한_번만_하고_최초_요청과_기한을_유지한다() {
        CouponException failure = CouponException.recoveryRequired(81L);
        failure.confirmIssuanceNotCommitted();
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        when(sequenceRecoveryService.recover(eq(81L), anyLong())).thenReturn(true);
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);

        var requests = org.mockito.ArgumentCaptor.forClass(CouponIssuanceRequest.class);
        var deadlines = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(batcher, times(2)).submitAndAwait(requests.capture(), deadlines.capture());
        assertThat(requests.getAllValues().get(0)).isSameAs(requests.getAllValues().get(1));
        assertThat(deadlines.getAllValues().get(0)).isEqualTo(deadlines.getAllValues().get(1));
    }

    @Test
    void 복구에_실패하거나_건너뛰면_발급을_재접수하지_않는다() {
        CouponException failure = CouponException.recoveryRequired(81L);
        failure.confirmIssuanceNotCommitted();
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(batcher).submitAndAwait(any(CouponIssuanceRequest.class), anyLong());
    }

    @Test
    void 미발급이_확인되지_않으면_복구에_성공해도_재접수하지_않는다() {
        CouponException failure = CouponException.recoveryRequired(81L);
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        when(sequenceRecoveryService.recover(eq(81L), anyLong())).thenReturn(true);
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(batcher).submitAndAwait(any(CouponIssuanceRequest.class), anyLong());
    }

    @Test
    void 복구_중_처리_기한이_끝나면_재접수하지_않는다() {
        CouponException failure = CouponException.recoveryRequired(81L);
        failure.confirmIssuanceNotCommitted();
        when(sequenceService.needsRecovery(81L)).thenReturn(true);
        when(sequenceRecoveryService.recover(eq(81L), anyLong())).thenAnswer(invocation -> {
            long deadline = invocation.getArgument(1);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(1))
                    .until(() -> deadline - System.nanoTime() <= 0);
            return true;
        });
        ReflectionTestUtils.setField(service, "properties", CouponTestProperties.with(java.util.Map.of("coupon.issue.batch.wait-timeout", java.time.Duration.ofMillis(20))));
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(batcher).submitAndAwait(any(CouponIssuanceRequest.class), anyLong());
    }

}
