package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.launchcatch.coupon.contract.CouponRedeemedEvent;
import com.launchcatch.coupon.dto.CouponQrResponse;
import com.launchcatch.coupon.dto.CouponUseRequest;
import com.launchcatch.coupon.dto.CouponUseResponse;
import com.launchcatch.coupon.entity.CouponDailyLimit;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponUsageHistory;
import com.launchcatch.coupon.entity.DiscountTargetType;
import com.launchcatch.coupon.entity.DiscountType;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository.QrReference;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.exception.ErrorCode;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreQueryService;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 QR 생성")
class CouponCommandServiceTest {

    private static final Long MEMBER_ID = 17L;
    private static final Long COUPON_ID = 1001L;
    private static final LocalDate ISSUE_DATE = LocalDate.of(2026, 10, 10);
    private static final LocalDateTime QR_EXPIRES_AT = LocalDateTime.of(2026, 10, 11, 12, 1);
    private static final UUID QR_TOKEN = UUID.randomUUID();

    @Mock MemberCouponRepository memberCouponRepository;
    @Mock CouponDailyLimitRepository couponDailyLimitRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock CouponIssuanceService couponIssuanceService;
    @Mock CandidateStateUpdater candidateStateUpdater;
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
            service = new CouponCommandService(repository, couponDailyLimitRepository, memberQuery,
                    mock(CouponEventRepository.class), mock(CouponIssuanceService.class),
                    new CouponQrImageEncoder(), Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC),
                    mock(CouponUsageHistoryRepository.class), mock(OwnerStatusQuery.class), mock(StoreQueryService.class), eventPublisher, mock(CandidateStateUpdater.class));
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


    @Nested
    @DisplayName("QR 사용")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class Redeem {

        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0, 0, 123456789);
        private static final LocalDateTime AUDITED_AT = NOW.plusSeconds(1);
        private static final UUID TOKEN = UUID.fromString("6c75d5bc-cae3-4eeb-a573-fc3bcb1673a5");
        private final MemberCouponRepository coupons = mock(MemberCouponRepository.class);
        private final CouponEventRepository events = mock(CouponEventRepository.class);
        private final CouponUsageHistoryRepository histories = mock(CouponUsageHistoryRepository.class);
        private final OwnerStatusQuery owners = mock(OwnerStatusQuery.class);
        private final StoreQueryService stores = mock(StoreQueryService.class);
        private final MemberQueryService members = mock(MemberQueryService.class);
        private final CouponEvent event = BeanUtils.instantiateClass(CouponEvent.class);
        private final QrReference reference = mock(QrReference.class);
        private CouponCommandService service;
        private MemberCoupon coupon;

        @BeforeEach
        void setUp() {
            service = new CouponCommandService(coupons, mock(CouponDailyLimitRepository.class), members, events,
                    mock(CouponIssuanceService.class), new CouponQrImageEncoder(),
                    Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE), histories, owners, stores, eventPublisher, mock(CandidateStateUpdater.class));
            coupon = MemberCoupon.issue(17L, 21L, UUID.randomUUID(), NOW.minusHours(1), NOW.plusHours(1));
            ReflectionTestUtils.setField(coupon, "id", 1001L);
            coupon.renewQr(TOKEN, NOW.minusSeconds(10));
        }

        @Test
        void 현재_권한과_스냅샷으로_사용하고_저장된_이력의_생성_시각을_반환한다() {
            ready();
            CouponUseResponse response = service.redeem(9L, request());
            assertThat(response.memberCouponId()).isEqualTo(1001L);
            assertThat(response.usageHistoryId()).isEqualTo(55L);
            assertThat(response.discountAmount()).isEqualTo(680);
            assertThat(response.useTime().toLocalDateTime()).isEqualTo(AUDITED_AT.withNano(123457000));
            verify(eventPublisher).publishEvent(new CouponRedeemedEvent(
                    "coupon-redeemed:1001", AUDITED_AT, 17, 91, 10, 21, 1001));
            assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.USED);
            assertThat(coupon.getQrToken()).isEqualTo(TOKEN);
            ArgumentCaptor<CouponUsageHistory> saved = ArgumentCaptor.forClass(CouponUsageHistory.class);
            verify(histories).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getCreatedAt()).isEqualTo(AUDITED_AT);
            assertThat(saved.getValue().getUpdatedAt()).isEqualTo(AUDITED_AT);
            assertThat(saved.getValue().getDiscountAmount()).isEqualTo(680);
        }

        @ParameterizedTest
        @CsvSource({"AMOUNT,3000,1500,3000", "PERCENT,15,3820,680"})
        void 메뉴_한_개의_스냅샷_차액을_할인한다(DiscountType type, int value, long discountedPrice, int expected) {
            ready();
            ReflectionTestUtils.setField(event, "discountType", type);
            ReflectionTestUtils.setField(event, "discountValue", value);
            ReflectionTestUtils.setField(event, "targetMenuDiscountedPrice", discountedPrice);

            CouponUseResponse response = service.redeem(9L, request());

            assertThat(response.discountAmount()).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({"3000,3000", "3005,3010"})
        void 전체_메뉴_정액은_주문_금액_없이_스냅샷_할인액을_반올림한다(int value, int expected) {
            ready();
            ReflectionTestUtils.setField(event, "discountTargetType", DiscountTargetType.ALL);
            ReflectionTestUtils.setField(event, "discountType", DiscountType.AMOUNT);
            ReflectionTestUtils.setField(event, "discountValue", value);

            assertThat(service.redeem(9L, request()).discountAmount()).isEqualTo(expected);
        }

        @Test
        void 전체_메뉴_정률은_사용하지_않고_상태와_이력을_유지한다() {
            ready();
            ReflectionTestUtils.setField(event, "discountTargetType", DiscountTargetType.ALL);

            assertError(CommonErrorCode.INTERNAL_ERROR);
            assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
            verify(eventPublisher, never()).publishEvent(any(CouponRedeemedEvent.class));
        }

        @ParameterizedTest
        @EnumSource(value = OwnerAccountStatus.class, names = {"ONBOARDING", "SUSPENDED", "WITHDRAWN"})
        void 비활성_점주는_쿠폰을_조회하기_전에_거절한다(OwnerAccountStatus state) {
            when(owners.findCurrentStatus(9L)).thenReturn(Optional.of(state));
            assertError(CouponErrorCode.OWNER_ACCOUNT_NOT_ALLOWED);
            verify(coupons, never()).findQrReference(any());
        }

        @Test
        void 점주가_없어도_상태_오류로_거절한다() {
            assertError(CouponErrorCode.OWNER_ACCOUNT_NOT_ALLOWED);
        }

        @Test
        void QR이_없으면_유효하지_않은_QR로_거절한다() {
            when(owners.findCurrentStatus(9L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            assertError(CouponErrorCode.QR_NOT_VALID);
        }

        @Test
        void 잠금_조회에서_쿠폰이_사라지면_QR_오류로_거절한다() {
            ready();
            when(coupons.findByIdForUpdate(1001L)).thenReturn(Optional.empty());
            assertError(CouponErrorCode.QR_NOT_VALID);
        }

        @Test
        void 매장_권한_검사는_사용_완료_판정보다_먼저_한다() {
            ready();
            ReflectionTestUtils.setField(coupon, "status", MemberCouponStatus.USED);
            when(stores.canManageStore(9L, 10L)).thenReturn(false);
            assertError(CouponErrorCode.COUPON_ACCESS_DENIED);
        }

        @Test
        void 사용_완료_QR은_만료와_회원_상태를_검사하기_전에_거절한다() {
            ready();
            ReflectionTestUtils.setField(coupon, "status", MemberCouponStatus.USED);
            ReflectionTestUtils.setField(coupon, "qrExpiresAt", NOW.minusDays(1));
            assertError(CouponErrorCode.COUPON_ALREADY_USED);
            verify(members, never()).findById(any());
        }

        @ParameterizedTest
        @EnumSource(value = MemberStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
        void 쿠폰_소유_회원의_현재_상태가_비활성이면_거절한다(MemberStatus state) {
            ready();
            when(members.findById(17L)).thenReturn(Optional.of(member(state)));
            assertError(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
        }

        @Test
        void 쿠폰_소유_회원이_없으면_거절한다() {
            ready();
            when(members.findById(17L)).thenReturn(Optional.empty());
            assertError(CouponErrorCode.MEMBER_ACCOUNT_NOT_ALLOWED);
        }

        @Test
        void 매장과_100m를_초과하면_상태와_이력을_바꾸지_않는다() {
            ready();
            when(stores.findAllById(List.of(10L))).thenReturn(List.of(store(38.0, 127.0)));
            assertError(CouponErrorCode.LOCATION_NOT_ALLOWED);
            assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
        }

        @Test
        void 스냅샷과_매장과_좌표_누락은_서버_정합성_오류다() {
            ready();
            when(events.findById(21L)).thenReturn(Optional.empty());
            assertError(CommonErrorCode.INTERNAL_ERROR);
            when(events.findById(21L)).thenReturn(Optional.of(event));
            when(stores.findAllById(List.of(10L))).thenReturn(List.of());
            assertError(CommonErrorCode.INTERNAL_ERROR);
            when(stores.findAllById(List.of(10L))).thenReturn(List.of(store(null, 127.0)));
            assertError(CommonErrorCode.INTERNAL_ERROR);
            when(stores.findAllById(List.of(10L))).thenReturn(List.of(store(37.5, null)));
            assertError(CommonErrorCode.INTERNAL_ERROR);
        }

        @Test
        void 공개_조회_장애를_계정_부재나_성공으로_바꾸지_않는다() {
            DataAccessResourceFailureException failure = new DataAccessResourceFailureException("test failure");
            when(owners.findCurrentStatus(9L)).thenThrow(failure);
            assertThatThrownBy(() -> service.redeem(9L, request())).isSameAs(failure);
        }

        private void ready() {
            when(owners.findCurrentStatus(9L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            when(coupons.findQrReference(TOKEN)).thenReturn(Optional.of(reference));
            when(reference.getId()).thenReturn(1001L);
            when(reference.getQrVersion()).thenReturn(coupon.getQrVersion());
            when(coupons.findByIdForUpdate(1001L)).thenReturn(Optional.of(coupon));
            when(events.findById(21L)).thenReturn(Optional.of(event));
            ReflectionTestUtils.setField(event, "id", 21L);
            ReflectionTestUtils.setField(event, "campaignId", 91L);
            ReflectionTestUtils.setField(event, "storeId", 10L);
            ReflectionTestUtils.setField(event, "discountTargetType", DiscountTargetType.MENU);
            ReflectionTestUtils.setField(event, "discountType", DiscountType.PERCENT);
            ReflectionTestUtils.setField(event, "discountValue", 15);
            ReflectionTestUtils.setField(event, "targetMenuPrice", 4500L);
            ReflectionTestUtils.setField(event, "targetMenuDiscountedPrice", 3820L);
            when(stores.canManageStore(9L, 10L)).thenReturn(true);
            when(stores.findAllById(List.of(10L))).thenReturn(List.of(store(37.5, 127.0)));
            when(members.findById(17L)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
            when(histories.saveAndFlush(any())).thenAnswer(invocation -> {
                CouponUsageHistory history = invocation.getArgument(0);
                ReflectionTestUtils.setField(history, "id", 55L);
                ReflectionTestUtils.setField(history, "createdAt", AUDITED_AT);
                ReflectionTestUtils.setField(history, "updatedAt", AUDITED_AT);
                return history;
            });
        }

        private MemberInfo member(MemberStatus state) {
            return new MemberInfo(17L, "테스트 회원", state, true, false, false, null, null, null, null);
        }

        private StoreInfo store(Double latitude, Double longitude) {
            return new StoreInfo(10L, "테스트 매장", "https://example.test/store.png", latitude, longitude);
        }

        private CouponUseRequest request() {
            return new CouponUseRequest(127.0, 37.5, TOKEN);
        }

        private void assertError(ErrorCode expected) {
            assertThatThrownBy(() -> service.redeem(9L, request())).isInstanceOfSatisfying(CouponException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(expected));
            verify(histories, never()).saveAndFlush(any());
        }
    }

    @Test
    void 마지막_재고의_소진_반영_장애는_발급_성공을_바꾸지_않는다() {
        var response = new CouponIssueResponse(1001L, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        var soldOut = new CouponIssuanceService.SoldOut(91L, ISSUE_DATE, ISSUE_DATE.atTime(12, 0));
        when(couponIssuanceService.issue(MEMBER_ID, 81L)).thenReturn(new CouponIssuanceService.Result(response, soldOut));
        doThrow(new QueryTimeoutException("candidate unavailable")).when(candidateStateUpdater)
                .markSoldOut(91L, ISSUE_DATE, soldOut.soldOutAt());

        assertThat(service.issue(MEMBER_ID, 81L)).isEqualTo(response);
        assertThat(logs.list).anyMatch(log -> log.getFormattedMessage().contains("COUPON_SOLD_OUT_NOTIFICATION_FAILED"));
    }

    @Test
    void 남은_재고가_있으면_소진을_통보하지_않는다() {
        var response = new CouponIssueResponse(1001L, ISSUE_DATE.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)));
        when(couponIssuanceService.issue(MEMBER_ID, 81L)).thenReturn(new CouponIssuanceService.Result(response, null));
        assertThat(service.issue(MEMBER_ID, 81L)).isEqualTo(response);
        verifyNoInteractions(candidateStateUpdater);
    }

    @Test
    void 소진으로_거절하면_원래_발급_시각으로_소진을_다시_통보한다() {
        var failure = new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED);
        var soldOut = new CouponIssuanceService.SoldOut(91L, ISSUE_DATE, ISSUE_DATE.atTime(11, 45));
        when(couponIssuanceService.issue(MEMBER_ID, 81L)).thenThrow(failure);
        when(couponIssuanceService.findSoldOut(81L)).thenReturn(Optional.of(soldOut));
        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verify(candidateStateUpdater).markSoldOut(91L, ISSUE_DATE, soldOut.soldOutAt());
    }

    @Test
    void 소진_복구_조회가_실패해도_원래_소진_거절을_유지한다() {
        var failure = new CouponException(CouponErrorCode.INVENTORY_EXHAUSTED);
        when(couponIssuanceService.issue(MEMBER_ID, 81L)).thenThrow(failure);
        when(couponIssuanceService.findSoldOut(81L)).thenThrow(new QueryTimeoutException("recovery unavailable"));
        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verifyNoInteractions(candidateStateUpdater);
        assertThat(logs.list).anyMatch(log -> log.getFormattedMessage().contains("COUPON_SOLD_OUT_RECOVERY_FAILED"));
    }

    @Test
    void 다른_발급_거절에는_소진을_통보하지_않는다() {
        var failure = new CouponException(CouponErrorCode.CAMPAIGN_NOT_WISHED);
        when(couponIssuanceService.issue(MEMBER_ID, 81L)).thenThrow(failure);
        assertThatThrownBy(() -> service.issue(MEMBER_ID, 81L)).isSameAs(failure);
        verifyNoInteractions(candidateStateUpdater);
    }
}
