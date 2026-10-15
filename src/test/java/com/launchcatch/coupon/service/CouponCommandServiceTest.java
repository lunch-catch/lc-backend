package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.launchcatch.coupon.CouponQrImageEncoder;
import com.launchcatch.coupon.dto.CouponQrResponse;
import com.launchcatch.coupon.entity.CouponDailyLimit;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 변경 서비스")
class CouponCommandServiceTest {

    private static final Long MEMBER_ID = 17L;
    private static final Long COUPON_ID = 1001L;
    private static final LocalDate ISSUE_DATE = LocalDate.of(2026, 10, 10);
    private static final LocalDateTime QR_EXPIRES_AT = LocalDateTime.of(2026, 10, 11, 12, 1);
    private static final UUID QR_TOKEN = UUID.randomUUID();

    @Mock MemberCouponRepository memberCouponRepository;
    @Mock CouponDailyLimitRepository couponDailyLimitRepository;
    @InjectMocks CouponCommandService service;

    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
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
            service = new CouponCommandService(repository, couponDailyLimitRepository, memberQuery, new CouponQrImageEncoder(),
                    Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC));
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
            return new QRCodeReader().decode(new BinaryBitmap(
                    new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
        }
    }

}
