package com.launchcatch.coupon.service;

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
import com.launchcatch.adserving.contract.CandidateStateUpdater;
import com.launchcatch.coupon.dto.CouponIssueResponse;
import com.launchcatch.coupon.CouponQrImageEncoder;
import com.launchcatch.coupon.dto.CouponQrResponse;
import com.launchcatch.coupon.entity.CouponDailyLimit;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.coupon.service.model.CouponIssuanceResult;
import com.launchcatch.coupon.service.model.CouponIssuanceRequest;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
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
    @Mock CouponLookUpService couponLookUpService;
    @Mock CandidateStateUpdater candidateStateUpdater;
    @Mock CouponSequenceService sequenceService;
    @Mock CouponSequenceRecoveryService sequenceRecoveryService;
    @Mock CouponIssueBatcher batcher;
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
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "event=COUPON_DELETE_FAILED memberId=17 memberCouponId=1001 reason=" + reason);
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
            service = new CouponCommandService(repository, couponDailyLimitRepository, memberQuery,
                    mock(CouponLookUpService.class), new CouponQrImageEncoder(),
                    Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC), mock(CandidateStateUpdater.class), mock(CouponSequenceService.class), mock(CouponSequenceRecoveryService.class),
                    mock(CouponIssueBatcher.class));
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
            CouponQrResponse response = new CouponQrResponse(new CouponQrImageEncoder().encode(token),
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
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "event=COUPON_QR_GENERATION_FAILED memberId=17 memberCouponId=1001 reason=" + error.getCode());
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
    void 마지막_재고의_소진_반영_장애는_발급_성공을_바꾸지_않는다() {
        CouponIssueResponse response = new CouponIssueResponse(1001L, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        CouponLookUpService.SoldOut soldOut = new CouponLookUpService.SoldOut(91L, ISSUE_DATE, ISSUE_DATE.atTime(12, 0));
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenReturn(new CouponIssuanceResult(response, true));
        when(couponLookUpService.findSoldOut(81L)).thenReturn(Optional.of(soldOut));
        doThrow(new QueryTimeoutException("candidate unavailable")).when(candidateStateUpdater)
                .markSoldOut(91L, ISSUE_DATE, soldOut.soldOutAt());

        assertThat(service.issue(MEMBER_ID, 81L)).isEqualTo(response);
        assertThat(logs.list).anyMatch(log -> log.getFormattedMessage().contains("COUPON_SOLD_OUT_NOTIFICATION_FAILED"));
    }

    @Test
    void 남은_재고가_있으면_소진을_통보하지_않는다() {
        CouponIssueResponse response = new CouponIssueResponse(1001L, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenReturn(new CouponIssuanceResult(response, false));
        assertThat(service.issue(MEMBER_ID, 81L)).isEqualTo(response);
        verifyNoInteractions(candidateStateUpdater);
    }

    @Test
    void 소진으로_거절하면_원래_발급_시각으로_소진을_다시_통보한다() {
        CouponException failure = new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED);
        CouponLookUpService.SoldOut soldOut = new CouponLookUpService.SoldOut(91L, ISSUE_DATE, ISSUE_DATE.atTime(11, 45));
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);
        when(couponLookUpService.findSoldOut(81L)).thenReturn(Optional.of(soldOut));
        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(candidateStateUpdater).markSoldOut(91L, ISSUE_DATE, soldOut.soldOutAt());
    }

    @Test
    void 소진_복구_조회가_실패해도_원래_소진_거절을_유지한다() {
        CouponException failure = new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED);
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);
        when(couponLookUpService.findSoldOut(81L)).thenThrow(new QueryTimeoutException("recovery unavailable"));
        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verifyNoInteractions(candidateStateUpdater);
        assertThat(logs.list).anyMatch(log -> log.getFormattedMessage().contains("COUPON_SOLD_OUT_RECOVERY_FAILED"));
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
        verifyNoInteractions(candidateStateUpdater);
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
        ReflectionTestUtils.setField(service, "waitTimeout", java.time.Duration.ofMillis(20));
        when(batcher.submitAndAwait(any(CouponIssuanceRequest.class), anyLong())).thenThrow(failure);

        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(batcher).submitAndAwait(any(CouponIssuanceRequest.class), anyLong());
    }

}
