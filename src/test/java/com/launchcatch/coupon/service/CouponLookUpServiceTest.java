package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
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
import com.launchcatch.coupon.repository.MemberCouponRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("쿠폰 조회 서비스")
class CouponLookUpServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Mock CouponDailyLimitRepository repository;
    @Mock MemberCouponRepository memberCouponRepository;
    @Mock CouponEventRepository couponEventRepository;
    @Mock CouponUsageHistoryRepository couponUsageHistoryRepository;

    private CouponLookUpService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC);
        service = configuredService(clock, 3);
    }

    @Test
    void 오늘_발급_기록이_없으면_0건이다() {
        when(repository.findIssuedCountByMemberIdAndBusinessDate(1L, TODAY)).thenReturn(Optional.empty());

        CouponIssuanceLimitResponse response = service.getIssuanceLimit(1L);

        assertThat(response).isEqualTo(new CouponIssuanceLimitResponse(TODAY, 0, 3, false));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 3, false",
            "2, 3, false",
            "3, 3, true",
            "2, 2, true",
            "3, 2, true"
    })
    void 설정된_한도와_누적_발급_횟수로_도달_여부를_계산한다(int issuedCount, int dailyLimit, boolean reached) {
        CouponLookUpService configured = configuredService(
                Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC), dailyLimit);
        when(repository.findIssuedCountByMemberIdAndBusinessDate(1L, TODAY))
                .thenReturn(Optional.of(issuedCount));

        CouponIssuanceLimitResponse response = configured.getIssuanceLimit(1L);

        assertThat(response).isEqualTo(new CouponIssuanceLimitResponse(TODAY, issuedCount, dailyLimit, reached));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-09T14:59:59.999999999Z, 2026-10-09",
            "2026-10-09T15:00:00Z, 2026-10-10"
    })
    void 영업일은_시계의_시간대와_관계없이_한국_자정을_기준으로_한다(Instant instant, LocalDate expectedDate) {
        Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
        CouponLookUpService boundaryService = configuredService(clock, 3);
        when(repository.findIssuedCountByMemberIdAndBusinessDate(1L, expectedDate)).thenReturn(Optional.of(2));

        CouponIssuanceLimitResponse response = boundaryService.getIssuanceLimit(1L);

        assertThat(response).isEqualTo(new CouponIssuanceLimitResponse(expectedDate, 2, 3, false));
    }

    @Test
    void 조회_실패를_발급_기록_없음으로_처리하지_않는다() {
        when(repository.findIssuedCountByMemberIdAndBusinessDate(1L, TODAY))
                .thenThrow(new DataAccessResourceFailureException("test database unavailable"));

        assertThatThrownBy(() -> service.getIssuanceLimit(1L))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Nested
    @DisplayName("쿠폰 통계 조회")
    class SummaryQuery {

        @Test
        void 요청한_회원의_집계_결과를_반환한다() {
            when(memberCouponRepository.findSummaryByMemberId(17L))
                    .thenReturn(new CouponSummaryResponse(12, 8, 24000));

            CouponSummaryResponse response = service.getSummary(17L);

            assertThat(response).isEqualTo(new CouponSummaryResponse(12, 8, 24000));
        }

        @Test
        void 조회_실패를_0건으로_처리하지_않는다() {
            when(memberCouponRepository.findSummaryByMemberId(17L))
                    .thenThrow(new DataAccessResourceFailureException("test database unavailable"));

            assertThatThrownBy(() -> service.getSummary(17L))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }
    }

    @Nested
    @DisplayName("쿠폰 상세 조회")
    class DetailQuery {

        private static final Long MEMBER_ID = 17L;
        private static final Long COUPON_ID = 1001L;
        private static final Long EVENT_ID = 81L;
        private static final LocalDateTime ISSUE_TIME = LocalDateTime.of(2026, 10, 10, 10, 30);
        private static final LocalDateTime START_TIME = LocalDateTime.of(2026, 10, 10, 11, 0);
        private static final LocalDateTime END_TIME = LocalDateTime.of(2026, 10, 10, 14, 0);
        private static final LocalDateTime USE_TIME = LocalDateTime.of(2026, 10, 10, 12, 10);
        private static final UUID QR_TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000004");

        @Mock MemberCouponRepository memberCouponRepository;
        @Mock CouponEventRepository couponEventRepository;
        @Mock CouponUsageHistoryRepository couponUsageHistoryRepository;
        @Mock CouponDailyLimitRepository couponDailyLimitRepository;

        private CouponLookUpService service;
        private Logger diagnosticLogger;
        private ListAppender<ILoggingEvent> diagnosticLogs;

        @BeforeEach
        void setUp() {
            service = serviceAt(Instant.parse("2026-10-10T03:00:00Z"));
            diagnosticLogger = (Logger) LoggerFactory.getLogger(CouponLookUpService.class);
            diagnosticLogs = new ListAppender<>();
            diagnosticLogs.start();
            diagnosticLogger.addAppender(diagnosticLogs);
        }

        @AfterEach
        void cleanUpLogs() {
            diagnosticLogger.detachAppender(diagnosticLogs);
            diagnosticLogs.stop();
        }

        @Test
        void 본인_쿠폰의_메뉴_스냅샷과_한국_시간을_반환한다() {
            stubCouponAndEvent(coupon(MemberCouponStatus.ISSUED), event());

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response).isEqualTo(new CouponDetailResponse(
                    COUPON_ID, 10L, "런치식당", "https://cdn.example.com/stores/10/logo-v1.jpg",
                    DiscountTargetType.MENU, DiscountType.AMOUNT, 3000,
                    new CouponDetailResponse.TargetMenu(701L, "숯불갈비 정식", 12000, 9000),
                    MemberCouponStatus.ISSUED, true, offset(ISSUE_TIME), offset(START_TIME), offset(END_TIME), null, null));
        }

        @Test
        void 전체_메뉴_할인이면_대상_메뉴는_null이다() {
            CouponEvent event = event();
            ReflectionTestUtils.setField(event, "discountTargetType", DiscountTargetType.ALL);
            ReflectionTestUtils.setField(event, "targetMenuId", null);
            ReflectionTestUtils.setField(event, "targetMenuName", null);
            ReflectionTestUtils.setField(event, "targetMenuPrice", null);
            ReflectionTestUtils.setField(event, "targetMenuDiscountedPrice", null);
            stubCouponAndEvent(coupon(MemberCouponStatus.ISSUED), event);

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response.discountTargetType()).isEqualTo(DiscountTargetType.ALL);
            assertThat(response.targetMenu()).isNull();
        }

        @Test
        void 대표_이미지가_없으면_이미지_URL은_null이다() {
            CouponEvent event = event();
            ReflectionTestUtils.setField(event, "storeImageUrl", null);
            stubCouponAndEvent(coupon(MemberCouponStatus.ISSUED), event);

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response.storeImageUrl()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://images.example.org/menu-v1.jpg?size=640&variant=2",
                "https://images.example.org/menu%20v1%231.jpg"
        })
        void 스냅샷의_이미지_URL을_다시_구성하거나_인코딩하지_않는다(String imageUrl) {
            CouponEvent event = event();
            ReflectionTestUtils.setField(event, "storeImageUrl", imageUrl);
            stubCouponAndEvent(coupon(MemberCouponStatus.ISSUED), event);

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response.storeImageUrl()).isEqualTo(imageUrl);
        }

        @ParameterizedTest
        @CsvSource({
                "2026-10-10T01:59:59.999999Z, ISSUED, false",
                "2026-10-10T02:00:00Z, ISSUED, true",
                "2026-10-10T04:59:59.999999Z, ISSUED, true",
                "2026-10-10T05:00:00Z, EXPIRED, false",
                "2026-10-10T05:00:00.000001Z, EXPIRED, false"
        })
        void 사용_시작은_포함하고_종료는_제외한다(Instant now, MemberCouponStatus expectedState, boolean expectedUsable) {
            service = serviceAt(now);
            MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
            stubCouponAndEvent(coupon, event());

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response.state()).isEqualTo(expectedState);
            assertThat(coupon.getStatus()).isEqualTo(expectedState);
            assertThat(response.usable()).isEqualTo(expectedUsable);
            assertThat(response.useTime()).isNull();
            assertThat(response.discountAmount()).isNull();
        }

        @Test
        void 사용_기간이_끝나면_쿠폰_상태를_만료로_변경한다() {
            service = serviceAt(Instant.parse("2026-10-10T05:00:00Z"));
            MemberCoupon coupon = coupon(MemberCouponStatus.ISSUED);
            stubCouponAndEvent(coupon, event());

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response.state()).isEqualTo(MemberCouponStatus.EXPIRED);
            assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.EXPIRED);
            assertThat(coupon.getQrToken()).isEqualTo(QR_TOKEN);
            assertThat(coupon.getQrVersion()).isEqualTo(4);
            assertThat(coupon.getQrExpiresAt()).isEqualTo(USE_TIME);
        }

        @ParameterizedTest
        @ValueSource(strings = {"2026-10-10T03:00:00Z", "2026-10-10T06:00:00Z"})
        void 사용_완료_쿠폰은_사용_시간과_관계없이_사용_상태와_확정된_사용_결과를_반환한다(String now) {
            service = serviceAt(Instant.parse(now));
            CouponEvent event = event();
            ReflectionTestUtils.setField(event, "discountType", DiscountType.PERCENT);
            ReflectionTestUtils.setField(event, "discountValue", 25);
            MemberCoupon coupon = coupon(MemberCouponStatus.USED);
            stubCouponAndEvent(coupon, event);
            when(couponUsageHistoryRepository.findByMemberCouponId(COUPON_ID)).thenReturn(Optional.of(history()));

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response.state()).isEqualTo(MemberCouponStatus.USED);
            assertThat(coupon.getStatus()).isEqualTo(MemberCouponStatus.USED);
            assertThat(response.usable()).isFalse();
            assertThat(response.useTime()).isEqualTo(offset(USE_TIME));
            assertThat(response.discountAmount()).isEqualTo(3000);
        }

        @Test
        void 저장된_상태가_만료이면_사용_시간_안이어도_사용할_수_없다() {
            stubCouponAndEvent(coupon(MemberCouponStatus.EXPIRED), event());

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response.state()).isEqualTo(MemberCouponStatus.EXPIRED);
            assertThat(response.usable()).isFalse();
            assertThat(response.useTime()).isNull();
            assertThat(response.discountAmount()).isNull();
        }

        @ParameterizedTest
        @EnumSource(CouponEventStatus.class)
        void 원본_이벤트_상태와_관계없이_발급된_쿠폰을_조회한다(CouponEventStatus eventStatus) {
            CouponEvent event = event();
            ReflectionTestUtils.setField(event, "status", eventStatus);
            stubCouponAndEvent(coupon(MemberCouponStatus.ISSUED), event);

            CouponDetailResponse response = service.getDetail(MEMBER_ID, COUPON_ID);

            assertThat(response.state()).isEqualTo(MemberCouponStatus.ISSUED);
            assertThat(response.usable()).isTrue();
            assertThat(response.storeName()).isEqualTo("런치식당");
        }

        @ParameterizedTest
        @ValueSource(longs = {1001L, 9999L})
        void 본인_소유권을_확인하지_못하면_존재_여부를_구분하지_않고_거절한다(Long memberCouponId) {
            when(memberCouponRepository.findByIdAndMemberId(memberCouponId, MEMBER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDetail(MEMBER_ID, memberCouponId))
                    .isInstanceOfSatisfying(CouponException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_ACCESS_DENIED);
                        assertThat(exception.getErrorCode().getCode()).isEqualTo("COUPON-015");
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(403);
                    });
        }

        @Test
        void 본인_소유이지만_삭제된_쿠폰은_찾을_수_없음으로_응답한다() {
            when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID))
                    .thenReturn(Optional.of(coupon(MemberCouponStatus.REMOVED)));

            assertThatThrownBy(() -> service.getDetail(MEMBER_ID, COUPON_ID))
                    .isInstanceOfSatisfying(CouponException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_NOT_FOUND);
                        assertThat(exception.getErrorCode().getCode()).isEqualTo("COUPON-001");
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(404);
                    });
        }

        @Test
        void 이벤트_스냅샷이_없으면_불완전한_응답을_반환하지_않는다() {
            when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID))
                    .thenReturn(Optional.of(coupon(MemberCouponStatus.ISSUED)));
            when(couponEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDetail(MEMBER_ID, COUPON_ID))
                    .isInstanceOfSatisfying(CouponException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.EVENT_SNAPSHOT_MISSING);
                        assertThat(exception.getErrorCode().getCode()).isEqualTo("COUPON-018");
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(500);
                        assertThat(exception.getMessage()).isEqualTo(exception.getErrorCode().getMessage());
                    });

            assertThat(diagnosticLogs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("COUPON-018", "memberCouponId=1001", "eventId=81");
            });
        }

        @Test
        void 사용_완료_쿠폰의_이력이_없으면_불완전한_사용_결과를_반환하지_않는다() {
            stubCouponAndEvent(coupon(MemberCouponStatus.USED), event());
            when(couponUsageHistoryRepository.findByMemberCouponId(COUPON_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDetail(MEMBER_ID, COUPON_ID))
                    .isInstanceOfSatisfying(CouponException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(CouponErrorCode.USAGE_HISTORY_MISSING);
                        assertThat(exception.getErrorCode().getCode()).isEqualTo("COUPON-019");
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(500);
                        assertThat(exception.getMessage()).isEqualTo(exception.getErrorCode().getMessage());
                    });

            assertThat(diagnosticLogs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("COUPON-019", "memberCouponId=1001", "eventId=81");
            });
        }

        // 지정한 시각을 사용하는 상세 조회 서비스를 구성한다.
        private CouponLookUpService serviceAt(Instant instant) {
            CouponLookUpService configured = new CouponLookUpService(
                    memberCouponRepository, couponEventRepository, couponUsageHistoryRepository, couponDailyLimitRepository,
                    Clock.fixed(instant, ZoneOffset.UTC));
            return configured;
        }

        // 본인 쿠폰과 연결된 이벤트 스냅샷이 조회되도록 설정한다.
        private void stubCouponAndEvent(MemberCoupon coupon, CouponEvent event) {
            when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID)).thenReturn(Optional.of(coupon));
            when(couponEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        }

        // 상세 조회에 사용할 회원 쿠폰의 상태와 사용 기간, QR 정보를 구성한다.
        private MemberCoupon coupon(MemberCouponStatus status) {
            MemberCoupon coupon = BeanUtils.instantiateClass(MemberCoupon.class);
            ReflectionTestUtils.setField(coupon, "id", COUPON_ID);
            ReflectionTestUtils.setField(coupon, "memberId", MEMBER_ID);
            ReflectionTestUtils.setField(coupon, "couponEventId", EVENT_ID);
            ReflectionTestUtils.setField(coupon, "status", status);
            ReflectionTestUtils.setField(coupon, "createdAt", ISSUE_TIME);
            ReflectionTestUtils.setField(coupon, "usableStartAt", START_TIME);
            ReflectionTestUtils.setField(coupon, "usableEndAt", END_TIME);
            ReflectionTestUtils.setField(coupon, "qrToken", QR_TOKEN);
            ReflectionTestUtils.setField(coupon, "qrVersion", 4);
            ReflectionTestUtils.setField(coupon, "qrExpiresAt", USE_TIME);
            return coupon;
        }

        // 상세 응답에 사용할 매장과 메뉴 할인 스냅샷을 구성한다.
        private CouponEvent event() {
            CouponEvent event = BeanUtils.instantiateClass(CouponEvent.class);
            ReflectionTestUtils.setField(event, "id", EVENT_ID);
            ReflectionTestUtils.setField(event, "storeId", 10L);
            ReflectionTestUtils.setField(event, "storeName", "런치식당");
            ReflectionTestUtils.setField(event, "storeImageUrl", "https://cdn.example.com/stores/10/logo-v1.jpg");
            ReflectionTestUtils.setField(event, "status", CouponEventStatus.ACTIVE);
            ReflectionTestUtils.setField(event, "discountTargetType", DiscountTargetType.MENU);
            ReflectionTestUtils.setField(event, "discountType", DiscountType.AMOUNT);
            ReflectionTestUtils.setField(event, "discountValue", 3000);
            ReflectionTestUtils.setField(event, "targetMenuId", 701L);
            ReflectionTestUtils.setField(event, "targetMenuName", "숯불갈비 정식");
            ReflectionTestUtils.setField(event, "targetMenuPrice", 12000L);
            ReflectionTestUtils.setField(event, "targetMenuDiscountedPrice", 9000L);
            return event;
        }

        // 사용 완료 쿠폰의 확정 할인액과 사용 시각을 구성한다.
        private CouponUsageHistory history() {
            CouponUsageHistory history = BeanUtils.instantiateClass(CouponUsageHistory.class);
            ReflectionTestUtils.setField(history, "memberCouponId", COUPON_ID);
            ReflectionTestUtils.setField(history, "createdAt", USE_TIME);
            ReflectionTestUtils.setField(history, "discountAmount", 3000);
            return history;
        }

        // 테스트에서 기대하는 시각에 한국 시간 오프셋을 적용한다.
        private OffsetDateTime offset(LocalDateTime time) {
            return time.atOffset(ZoneOffset.ofHours(9));
        }
    }

    // 지정한 시계와 일일 발급 한도를 사용하는 조회 서비스를 구성한다.
    private CouponLookUpService configuredService(Clock clock, int dailyLimit) {
        CouponLookUpService configured = new CouponLookUpService(memberCouponRepository, couponEventRepository, couponUsageHistoryRepository, repository, clock);
        ReflectionTestUtils.setField(configured, "dailyLimit", dailyLimit);
        return configured;
    }
}
