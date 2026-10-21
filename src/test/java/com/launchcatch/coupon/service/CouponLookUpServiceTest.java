package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.coupon.CouponListFilter;
import com.launchcatch.coupon.contract.CouponExpiredEvent;
import com.launchcatch.coupon.dto.CouponDetailResponse;
import com.launchcatch.coupon.dto.CouponEventStatusResponse.CannotIssueReason;
import com.launchcatch.coupon.dto.CouponEventStatusResponse.EventState;
import com.launchcatch.coupon.dto.CouponEventStatusResponse.Item;
import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.dto.CouponSummaryResponse;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponEventStatus;
import com.launchcatch.coupon.entity.CouponInventoryStatus;
import com.launchcatch.coupon.entity.CouponUsageHistory;
import com.launchcatch.coupon.entity.DiscountTargetType;
import com.launchcatch.coupon.entity.DiscountType;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponInventoryRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("회원 쿠폰 조회 서비스")
class CouponLookUpServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Mock CouponDailyLimitRepository repository;
    @Mock MemberCouponRepository memberCouponRepository;
    @Mock CouponEventRepository couponEventRepository;
    @Mock CouponUsageHistoryRepository couponUsageHistoryRepository;
    @Mock StoreQueryService stores;
    @Mock WishlistQueryService wishes;
    @Mock CampaignQueryService campaignQueryService;
    @Mock MemberQueryService memberQueryService;
    @Mock ApplicationEventPublisher publisher;
    @Mock CouponInventoryRepository inventories;

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
    @DisplayName("쿠폰 목록 조회")
    class ListQuery {

        private static final Long MEMBER_ID = 17L;
        private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);
        private static final LocalDateTime ISSUE_TIME = NOW.minusHours(2);

        @Mock MemberCouponRepository memberCouponRepository;
        @Mock CouponEventRepository couponEventRepository;
        @Mock CouponUsageHistoryRepository couponUsageHistoryRepository;
        @Mock CouponDailyLimitRepository couponDailyLimitRepository;

        private CouponLookUpService service;

        @BeforeEach
        void setUp() {
            service = new CouponLookUpService(
                    memberCouponRepository, couponEventRepository, couponUsageHistoryRepository, couponDailyLimitRepository, campaignQueryService, memberQueryService,
                    Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC), stores, wishes, publisher, mock(CouponInventoryRepository.class));
        }

        @ParameterizedTest
        @EnumSource(CouponListFilter.class)
        void 쿠폰이_없으면_모든_필터에서_빈_목록과_null_커서를_반환한다(CouponListFilter listFilter) {
            String filter = switch (listFilter) {
                case ALL -> null;
                case USABLE -> "usable = true";
                case ISSUED, USED, EXPIRED -> "state = \"" + listFilter.name() + "\"";
            };
            when(memberCouponRepository.findPage(MEMBER_ID, listFilter, NOW, null, null, 21))
                    .thenReturn(List.of());

            assertThat(service.getCoupons(MEMBER_ID, filter, 20, null))
                    .isEqualTo(CursorPageResponse.of(List.of(), null));
        }

        @Test
        void 마지막_페이지는_단건과_같은_메뉴_스냅샷과_사용_결과를_반환한다() {
            MemberCoupon coupon = coupon(1001L, MemberCouponStatus.USED, NOW.minusHours(1), NOW.plusHours(2));
            CouponUsageHistory history = BeanUtils.instantiateClass(CouponUsageHistory.class);
            ReflectionTestUtils.setField(history, "memberCouponId", 1001L);
            ReflectionTestUtils.setField(history, "createdAt", NOW.minusMinutes(10));
            ReflectionTestUtils.setField(history, "discountAmount", 3000);
            when(memberCouponRepository.findPage(MEMBER_ID, CouponListFilter.USED, NOW, null, null, 21))
                    .thenReturn(List.of(coupon));
            when(couponEventRepository.findAllById(List.of(81L))).thenReturn(List.of(event()));
            when(couponUsageHistoryRepository.findAllByMemberCouponIdIn(List.of(1001L))).thenReturn(List.of(history));

            CursorPageResponse<CouponDetailResponse> response = service.getCoupons(MEMBER_ID, "state = \"USED\"", 20, null);

            assertThat(response.nextPageToken()).isNull();
            assertThat(response.items()).containsExactly(new CouponDetailResponse(
                    1001L, 10L, "런치식당", "https://cdn.example.com/stores/10/logo-v1.jpg",
                    DiscountTargetType.MENU, DiscountType.AMOUNT, 3000,
                    new CouponDetailResponse.TargetMenu(701L, "갈비 정식", 12000, 9000),
                    MemberCouponStatus.USED, false, ISSUE_TIME.atOffset(ZoneOffset.ofHours(9)),
                    NOW.minusHours(1).atOffset(ZoneOffset.ofHours(9)), NOW.plusHours(2).atOffset(ZoneOffset.ofHours(9)),
                    NOW.minusMinutes(10).atOffset(ZoneOffset.ofHours(9)), 3000));
        }

        @Test
        void 한_건_더_있으면_반환한_마지막_쿠폰으로_다음_커서를_만든다() {
            when(memberCouponRepository.findPage(MEMBER_ID, CouponListFilter.ALL, NOW, null, null, 3))
                    .thenReturn(List.of(coupon(1003L, MemberCouponStatus.ISSUED, NOW, NOW.plusHours(1)),
                            coupon(1002L, MemberCouponStatus.ISSUED, NOW, NOW.plusHours(1)),
                            coupon(1001L, MemberCouponStatus.ISSUED, NOW, NOW.plusHours(1))));
            when(couponEventRepository.findAllById(List.of(81L))).thenReturn(List.of(event()));

            CursorPageResponse<CouponDetailResponse> response = service.getCoupons(MEMBER_ID, null, 2, null);

            assertThat(response.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1003L, 1002L);
            assertThat(PageTokens.decode(response.nextPageToken())).isEqualTo(new PageCursor(1002L, ISSUE_TIME + "|ALL"));
        }

        @ParameterizedTest
        @CsvSource({"-1, 1, ISSUED, true", "0, 1, ISSUED, true", "1, 2, ISSUED, false",
                "-2, 0, EXPIRED, false", "-2, -1, EXPIRED, false"})
        void 조회_시각으로_상태와_사용_가능_여부를_계산하고_만료된_쿠폰의_상태를_변경한다(
                int startHours, int endHours, MemberCouponStatus expectedState, boolean expectedUsable) {
            MemberCoupon coupon = coupon(1001L, MemberCouponStatus.ISSUED, NOW.plusHours(startHours), NOW.plusHours(endHours));
            when(memberCouponRepository.findPage(MEMBER_ID, CouponListFilter.ALL, NOW, null, null, 21))
                    .thenReturn(List.of(coupon));
            when(couponEventRepository.findAllById(List.of(81L))).thenReturn(List.of(event()));

            CouponDetailResponse response = service.getCoupons(MEMBER_ID, null, 20, null).items().getFirst();

            assertThat(response.state()).isEqualTo(expectedState);
            assertThat(response.usable()).isEqualTo(expectedUsable);
            assertThat(coupon.getStatus()).isEqualTo(expectedState);
        }

        @Test
        void 전체_주문_할인은_메뉴와_이미지가_없을_수_있다() {
            CouponEvent event = event();
            ReflectionTestUtils.setField(event, "discountTargetType", DiscountTargetType.ALL);
            ReflectionTestUtils.setField(event, "storeImageUrl", null);
            when(memberCouponRepository.findPage(MEMBER_ID, CouponListFilter.ALL, NOW, null, null, 21))
                    .thenReturn(List.of(coupon(1001L, MemberCouponStatus.EXPIRED, NOW.minusHours(2), NOW.minusHours(1))));
            when(couponEventRepository.findAllById(List.of(81L))).thenReturn(List.of(event));

            CouponDetailResponse response = service.getCoupons(MEMBER_ID, null, 20, null).items().getFirst();

            assertThat(response.targetMenu()).isNull();
            assertThat(response.storeImageUrl()).isNull();
            assertThat(response.useTime()).isNull();
            assertThat(response.discountAmount()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "ISSUED", "state = \"REMOVED\"", "usable = false", "state = \"USED\" AND usable = true"})
        void 지원하지_않는_필터는_거절한다(String filter) {
            assertThatThrownBy(() -> service.getCoupons(MEMBER_ID, filter, 20, null))
                    .isInstanceOfSatisfying(CouponException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "not-a-token", "cDox", "cDoxfA"})
        void 해석할_수_없는_커서는_첫_페이지로_대체하지_않고_거절한다(String pageToken) {
            assertThatThrownBy(() -> service.getCoupons(MEMBER_ID, null, 20, pageToken))
                    .isInstanceOfSatisfying(CouponException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-a-time|ALL", "2026-10-10T10:00|USED", "2026-10-10T10:00", "2026-10-10T10:00|ALL|extra"})
        void 커서_시각과_필터를_검증한다(String sortValue) {
            String token = PageTokens.encode(new PageCursor(1001L, sortValue));

            assertThatThrownBy(() -> service.getCoupons(MEMBER_ID, null, 20, token))
                    .isInstanceOfSatisfying(CouponException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, -1L})
        void 커서_ID는_양수여야_한다(long id) {
            String token = PageTokens.encode(new PageCursor(id, ISSUE_TIME + "|ALL"));

            assertThatThrownBy(() -> service.getCoupons(MEMBER_ID, null, 20, token))
                    .isInstanceOfSatisfying(CouponException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        }

        @Test
        void 다음_페이지에서는_커서가_가리킨_발급_시각과_ID로_조회한다() {
            String token = PageTokens.encode(new PageCursor(1002L, ISSUE_TIME + "|ALL"));
            when(memberCouponRepository.findPage(MEMBER_ID, CouponListFilter.ALL, NOW, ISSUE_TIME, 1002L, 21))
                    .thenReturn(List.of(coupon(1001L, MemberCouponStatus.ISSUED, NOW, NOW.plusHours(1))));
            when(couponEventRepository.findAllById(List.of(81L))).thenReturn(List.of(event()));

            CursorPageResponse<CouponDetailResponse> response = service.getCoupons(MEMBER_ID, null, 20, token);

            assertThat(response.items()).extracting(CouponDetailResponse::memberCouponId).containsExactly(1001L);
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        void 이벤트_스냅샷이_없으면_내부_데이터_불일치로_처리한다() {
            when(memberCouponRepository.findPage(eq(MEMBER_ID), any(), eq(NOW), any(), any(), anyInt()))
                    .thenReturn(List.of(coupon(1001L, MemberCouponStatus.ISSUED, NOW, NOW.plusHours(1))));

            assertThatThrownBy(() -> service.getCoupons(MEMBER_ID, null, 20, null))
                    .isInstanceOfSatisfying(CouponException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));
        }

        @Test
        void 사용_완료_쿠폰의_사용_이력이_없으면_오류로_처리한다() {
            when(memberCouponRepository.findPage(MEMBER_ID, CouponListFilter.ALL, NOW, null, null, 21))
                    .thenReturn(List.of(coupon(1001L, MemberCouponStatus.USED, NOW, NOW.plusHours(1))));
            when(couponEventRepository.findAllById(List.of(81L))).thenReturn(List.of(event()));

            assertThatThrownBy(() -> service.getCoupons(MEMBER_ID, null, 20, null))
                    .isInstanceOfSatisfying(CouponException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));
        }

        @Test
        void 다음_페이지_확인용_쿠폰은_만료로_변경하지_않는다() {
            MemberCoupon returned = coupon(1002L, MemberCouponStatus.ISSUED, NOW.minusHours(2), NOW);
            MemberCoupon lookahead = coupon(1001L, MemberCouponStatus.ISSUED, NOW.minusHours(2), NOW);
            ReflectionTestUtils.setField(lookahead, "couponEventId", 99L);
            when(memberCouponRepository.findPage(MEMBER_ID, CouponListFilter.ALL, NOW, null, null, 2))
                    .thenReturn(List.of(returned, lookahead));
            when(couponEventRepository.findAllById(List.of(81L))).thenReturn(List.of(event()));

            var result = service.getCoupons(MEMBER_ID, null, 1, null);

            assertThat(result.items()).extracting(CouponDetailResponse::state).containsExactly(MemberCouponStatus.EXPIRED);
            assertThat(returned.getStatus()).isEqualTo(MemberCouponStatus.EXPIRED);
            assertThat(lookahead.getStatus()).isEqualTo(MemberCouponStatus.ISSUED);
            assertThat(result.nextPageToken()).isNotNull();
        }

        // 목록 조회에 사용할 회원 쿠폰의 식별자와 상태, 사용 기간을 구성한다.
        private MemberCoupon coupon(Long id, MemberCouponStatus status, LocalDateTime start, LocalDateTime end) {
            MemberCoupon coupon = BeanUtils.instantiateClass(MemberCoupon.class);
            ReflectionTestUtils.setField(coupon, "id", id);
            ReflectionTestUtils.setField(coupon, "memberId", MEMBER_ID);
            ReflectionTestUtils.setField(coupon, "couponEventId", 81L);
            ReflectionTestUtils.setField(coupon, "status", status);
            ReflectionTestUtils.setField(coupon, "createdAt", ISSUE_TIME);
            ReflectionTestUtils.setField(coupon, "usableStartAt", start);
            ReflectionTestUtils.setField(coupon, "usableEndAt", end);
            return coupon;
        }

        // 목록 응답에 사용할 매장과 메뉴 할인 스냅샷을 구성한다.
        private CouponEvent event() {
            CouponEvent event = BeanUtils.instantiateClass(CouponEvent.class);
            ReflectionTestUtils.setField(event, "id", 81L);
            ReflectionTestUtils.setField(event, "storeId", 10L);
            ReflectionTestUtils.setField(event, "campaignId", 91L);
            ReflectionTestUtils.setField(event, "storeName", "런치식당");
            ReflectionTestUtils.setField(event, "storeImageUrl", "https://cdn.example.com/stores/10/logo-v1.jpg");
            ReflectionTestUtils.setField(event, "discountTargetType", DiscountTargetType.MENU);
            ReflectionTestUtils.setField(event, "discountType", DiscountType.AMOUNT);
            ReflectionTestUtils.setField(event, "discountValue", 3000);
            ReflectionTestUtils.setField(event, "targetMenuId", 701L);
            ReflectionTestUtils.setField(event, "targetMenuName", "갈비 정식");
            ReflectionTestUtils.setField(event, "targetMenuPrice", 12000L);
            ReflectionTestUtils.setField(event, "targetMenuDiscountedPrice", 9000L);
            return event;
        }
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
            verify(publisher).publishEvent(new CouponExpiredEvent("coupon-expired:" + COUPON_ID, END_TIME,
                    MEMBER_ID, 91L, 10L, EVENT_ID, COUPON_ID));
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
                        assertThat(exception.getErrorCode().getCode()).isEqualTo("COUPON-011");
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(403);
                    });
            assertThat(diagnosticLogs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "event=COUPON_DETAIL_FAILED memberId=17 memberCouponId=" + memberCouponId + " reason=COUPON-011");
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
            assertThat(diagnosticLogs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "event=COUPON_DETAIL_FAILED memberId=17 memberCouponId=1001 reason=COUPON-001");
            });
        }

        @Test
        void 이벤트_스냅샷이_없으면_불완전한_응답을_반환하지_않는다() {
            when(memberCouponRepository.findByIdAndMemberId(COUPON_ID, MEMBER_ID))
                    .thenReturn(Optional.of(coupon(MemberCouponStatus.ISSUED)));
            when(couponEventRepository.findById(EVENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDetail(MEMBER_ID, COUPON_ID))
                    .isInstanceOfSatisfying(CouponException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR);
                        assertThat(exception.getErrorCode().getCode()).isEqualTo("COMMON-001");
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(500);
                        assertThat(exception.getMessage()).isEqualTo(exception.getErrorCode().getMessage());
                    });

            assertThat(diagnosticLogs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "event=COUPON_DATA_INTEGRITY_FAILED reason=EVENT_SNAPSHOT_MISSING code=COMMON-001 memberCouponId=1001 eventId=81");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }

        @Test
        void 사용_완료_쿠폰의_이력이_없으면_불완전한_사용_결과를_반환하지_않는다() {
            stubCouponAndEvent(coupon(MemberCouponStatus.USED), event());
            when(couponUsageHistoryRepository.findByMemberCouponId(COUPON_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getDetail(MEMBER_ID, COUPON_ID))
                    .isInstanceOfSatisfying(CouponException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR);
                        assertThat(exception.getErrorCode().getCode()).isEqualTo("COMMON-001");
                        assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(500);
                        assertThat(exception.getMessage()).isEqualTo(exception.getErrorCode().getMessage());
                    });

            assertThat(diagnosticLogs.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo(
                        "event=COUPON_DATA_INTEGRITY_FAILED reason=USAGE_HISTORY_MISSING code=COMMON-001 memberCouponId=1001 eventId=81");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }

        // 지정한 시각을 사용하는 상세 조회 서비스를 구성한다.
        private CouponLookUpService serviceAt(Instant instant) {
            CouponLookUpService configured = new CouponLookUpService(
                    memberCouponRepository, couponEventRepository, couponUsageHistoryRepository, couponDailyLimitRepository, campaignQueryService, memberQueryService,
                    Clock.fixed(instant, ZoneOffset.UTC), stores, wishes, publisher, mock(CouponInventoryRepository.class));
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
            ReflectionTestUtils.setField(event, "campaignId", 91L);
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
        CouponLookUpService configured = new CouponLookUpService(memberCouponRepository, couponEventRepository, couponUsageHistoryRepository, repository, campaignQueryService, memberQueryService, clock, stores, wishes, publisher, inventories);
        ReflectionTestUtils.setField(configured, "dailyLimit", dailyLimit);
        ReflectionTestUtils.setField(configured, "issueStartTime", LocalTime.of(11, 0));
        ReflectionTestUtils.setField(configured, "issueEndTime", LocalTime.of(14, 0));
        return configured;
    }

    @Nested
    @DisplayName("매장별 쿠폰 이벤트 상태 조회")
    class EventStatusQuery {

        private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
        private static final List<CouponEventStatus> STATES = List.of(CouponEventStatus.ACTIVE, CouponEventStatus.SCHEDULED);

        @BeforeEach
        void setUp() {
            service = eventServiceAt("2026-10-14T03:00:00.123456Z");
            lenient().when(stores.findAllById(any())).thenAnswer(invocation -> {
                List<Long> ids = invocation.getArgument(0);
                return ids.stream().map(id -> new StoreInfo(id, "매장", null, 37.5, 127.0)).toList();
            });
        }

        @Test
        void 매장_조회_순서와_관계없이_존재하는_매장만_입력_순서대로_반환한다() {
            when(stores.findAllById(List.of(30L, 999L, 10L))).thenReturn(List.of(
                    new StoreInfo(10L, "매장1", null, 37.5, 127.0),
                    new StoreInfo(30L, "매장3", null, 37.5, 127.0)));
            assertThat(service.getEventStatus(17L, List.of(30L, 999L, 10L)).items())
                    .extracting(Item::storeId).containsExactly(30L, 10L);
        }

        @Test
        void 매장이_모두_없으면_빈_목록을_반환한다() {
            when(stores.findAllById(List.of(999L))).thenReturn(List.of());
            assertThat(service.getEventStatus(17L, List.of(999L)).items()).isEmpty();
            verifyNoInteractions(couponEventRepository, wishes);
        }

        @Test
        void 매장_조회_장애를_없는_매장으로_바꾸지_않는다() {
            var failure = new DataAccessResourceFailureException("store unavailable");
            when(stores.findAllById(List.of(10L))).thenThrow(failure);
            assertThatThrownBy(() -> service.getEventStatus(17L, List.of(10L))).isSameAs(failure);
        }

        @Test
        void 찜한_캠페인의_열린_이벤트는_발급_가능으로_안내한다() {
            stubSingleEvent();
            when(memberQueryService.findById(17L)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
            when(wishes.findWishedCampaignIds(17L)).thenReturn(List.of(999L, 501L));
            var stock = quantity(501L, 2);
            when(couponEventRepository.findQuantitiesByEventIdsAndStatus(List.of(501L), CouponInventoryStatus.AVAILABLE))
                    .thenReturn(List.of(stock));
            var item = service.getEventStatus(17L, List.of(10L)).items().getFirst();
            assertThat(item.wished()).isTrue();
            assertThat(item.canIssue()).isTrue();
            assertThat(item.cannotIssueReason()).isNull();
        }

        @Test
        void 찜_목록_조회_장애를_찜_없음으로_바꾸지_않는다() {
            stubSingleEvent();
            var failure = new DataAccessResourceFailureException("wishlist unavailable");
            when(wishes.findWishedCampaignIds(17L)).thenThrow(failure);
            assertThatThrownBy(() -> service.getEventStatus(17L, List.of(10L))).isSameAs(failure);
        }

        @Test
        void 조회_결과를_입력_순서대로_반환하며_스냅샷과_발급_이력을_반영한다() {
            List<Long> storeIds = List.of(20L, 10L, 30L, 40L);
            var stock = quantity(501L, 42);
            when(couponEventRepository.findByStoreIdInAndBusinessDateAndStatusIn(storeIds, TODAY, STATES))
                    .thenReturn(List.of(event(501L, 10L, CouponEventStatus.ACTIVE), event(502L, 20L, CouponEventStatus.SCHEDULED)));
            when(couponEventRepository.findQuantitiesByEventIdsAndStatus(List.of(501L, 502L), CouponInventoryStatus.AVAILABLE))
                    .thenReturn(List.of(stock));
            when(memberCouponRepository.findIssuedEventIds(17L, List.of(501L, 502L))).thenReturn(List.of(501L));
            when(campaignQueryService.findByStoreIdIn(storeIds)).thenReturn(List.of(
                    campaign(10L, CampaignStatus.ACTIVE), campaign(20L, CampaignStatus.SCHEDULED),
                    campaign(30L, CampaignStatus.ACTIVE), campaign(40L, CampaignStatus.PAUSED)));
            when(memberQueryService.findById(17L)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
            when(repository.findIssuedCountByMemberIdAndBusinessDate(17L, TODAY)).thenReturn(Optional.of(2));

            var response = service.getEventStatus(17L, storeIds);

            assertThat(response.serverTime()).isEqualTo(OffsetDateTime.parse("2026-10-14T12:00:00.123456+09:00"));
            assertThat(response.items()).extracting(Item::storeId).containsExactly(20L, 10L, 30L, 40L);
            assertThat(response.items().get(0).eventState()).isEqualTo(EventState.SCHEDULED);
            Item issued = response.items().get(1);
            assertThat(issued.eventId()).isEqualTo(501L);
            assertThat(issued.remainingQuantity()).isEqualTo(42);
            assertThat(issued.eventState()).isEqualTo(EventState.OPEN);
            assertThat(issued.hasIssuedToday()).isTrue();
            assertThat(issued.cannotIssueReason()).isEqualTo(CannotIssueReason.ALREADY_ISSUED);
            assertThat(issued.usableStartTime()).isEqualTo(OffsetDateTime.parse("2026-10-14T12:30:00+09:00"));
            assertThat(issued.usableEndTime()).isEqualTo(OffsetDateTime.parse("2026-10-14T15:00:00+09:00"));
            assertThat(response.items().get(2)).isEqualTo(new Item(30L, true, null, EventState.NO_EVENT, 0,
                    null, null, false, false, false, CannotIssueReason.NO_EVENT));
            assertThat(response.items().get(3).hasCampaign()).isFalse();
            assertThat(response.items()).allMatch(item -> !item.wished() && !item.canIssue());
        }

        @ParameterizedTest
        @CsvSource({"ACTIVE, NOT_WISHED", "SUSPENDED, ACCOUNT_NOT_ELIGIBLE", "WITHDRAWN, ACCOUNT_NOT_ELIGIBLE"})
        void 현재_회원_상태를_발급_가능_사유에_반영한다(MemberStatus status, CannotIssueReason expected) {
            stubSingleEvent();
            when(memberQueryService.findById(17L)).thenReturn(Optional.of(member(status)));

            Item item = service.getEventStatus(17L, List.of(10L)).items().getFirst();

            assertThat(item.cannotIssueReason()).isEqualTo(expected);
            assertThat(item.eventState()).isEqualTo(EventState.SOLD_OUT);
        }

        @Test
        void 회원이_없으면_계정_자격이_없는_것으로_안내한다() {
            stubSingleEvent();

            assertThat(service.getEventStatus(17L, List.of(10L)).items().getFirst().cannotIssueReason())
                    .isEqualTo(CannotIssueReason.ACCOUNT_NOT_ELIGIBLE);
        }

        @Test
        void 일일_한도에_도달하면_찜_미충족보다_한도_사유를_우선한다() {
            stubSingleEvent();
            when(memberQueryService.findById(17L)).thenReturn(Optional.of(member(MemberStatus.ACTIVE)));
            when(repository.findIssuedCountByMemberIdAndBusinessDate(17L, TODAY)).thenReturn(Optional.of(3));

            assertThat(service.getEventStatus(17L, List.of(10L)).items().getFirst().cannotIssueReason())
                    .isEqualTo(CannotIssueReason.DAILY_LIMIT_REACHED);
        }

        @ParameterizedTest
        @CsvSource({"2026-10-13T14:59:59.999999Z, 2026-10-13", "2026-10-13T15:00:00Z, 2026-10-14"})
        void 한국_자정을_기준으로_이벤트의_영업일을_조회한다(Instant instant, LocalDate expectedDate) {
            service = eventServiceAt(instant.toString());
            when(couponEventRepository.findByStoreIdInAndBusinessDateAndStatusIn(List.of(10L), expectedDate, STATES))
                    .thenReturn(List.of(event(501L, 10L, CouponEventStatus.ACTIVE)));

            var response = service.getEventStatus(17L, List.of(10L));

            assertThat(response.serverTime().toLocalDate()).isEqualTo(expectedDate);
            assertThat(response.items().getFirst().eventId()).isEqualTo(501L);
        }

        @Test
        void 이벤트가_없으면_입력한_매장을_NO_EVENT로_반환한다() {
            var response = service.getEventStatus(17L, List.of(10L));

            assertThat(response.items()).containsExactly(new Item(10L, false, null, EventState.NO_EVENT, 0,
                    null, null, false, false, false, CannotIssueReason.NO_EVENT));
        }

        @Test
        void 같은_매장의_오늘_이벤트가_중복되면_임의로_고르지_않는다() {
            when(couponEventRepository.findByStoreIdInAndBusinessDateAndStatusIn(List.of(10L), TODAY, STATES))
                    .thenReturn(List.of(event(501L, 10L, CouponEventStatus.ACTIVE), event(502L, 10L, CouponEventStatus.SCHEDULED)));

            assertThatThrownBy(() -> service.getEventStatus(17L, List.of(10L)))
                    .isInstanceOfSatisfying(CouponException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));
        }

        @Test
        void 재고_조회_실패를_품절로_바꾸지_않는다() {
            stubSingleEvent();
            var failure = new DataAccessResourceFailureException("inventory unavailable");
            when(couponEventRepository.findQuantitiesByEventIdsAndStatus(List.of(501L), CouponInventoryStatus.AVAILABLE))
                    .thenThrow(failure);

            assertThatThrownBy(() -> service.getEventStatus(17L, List.of(10L))).isSameAs(failure);
        }

        @Test
        void 회원_조회_실패를_자격_없음으로_바꾸지_않는다() {
            stubSingleEvent();
            var failure = new DataAccessResourceFailureException("member unavailable");
            when(memberQueryService.findById(17L)).thenThrow(failure);

            assertThatThrownBy(() -> service.getEventStatus(17L, List.of(10L))).isSameAs(failure);
        }

        @ParameterizedTest
        @CsvSource({
                "ACTIVE, 2, 10:59:59.999999999, SCHEDULED",
                "ACTIVE, 2, 11:00:00, OPEN",
                "ACTIVE, 2, 13:59:59.999999999, OPEN",
                "ACTIVE, 2, 14:00:00, CLOSED",
                "ACTIVE, 0, 10:59:59.999999999, SCHEDULED",
                "ACTIVE, 0, 11:00:00, SOLD_OUT",
                "ACTIVE, 0, 13:59:59.999999999, SOLD_OUT",
                "ACTIVE, 0, 14:00:00, CLOSED",
                "SCHEDULED, 2, 12:00:00, SCHEDULED",
                "SCHEDULED, 0, 12:00:00, SCHEDULED",
                "SCHEDULED, 2, 14:00:00, CLOSED"
        })
        void 발급_시간과_운영_상태를_재고보다_먼저_판정한다(
                CouponEventStatus status, int quantity, LocalTime now, EventState expected) {
            service = eventServiceAt(TODAY.atTime(now).toInstant(ZoneOffset.ofHours(9)).toString());
            var stock = quantity(501L, quantity);
            when(couponEventRepository.findByStoreIdInAndBusinessDateAndStatusIn(List.of(10L), TODAY, STATES))
                    .thenReturn(List.of(event(501L, 10L, status)));
            when(couponEventRepository.findQuantitiesByEventIdsAndStatus(List.of(501L), CouponInventoryStatus.AVAILABLE))
                    .thenReturn(List.of(stock));

            assertThat(service.getEventStatus(17L, List.of(10L)).items().getFirst().eventState()).isEqualTo(expected);
        }

        private void stubSingleEvent() {
            when(couponEventRepository.findByStoreIdInAndBusinessDateAndStatusIn(List.of(10L), TODAY, STATES))
                    .thenReturn(List.of(event(501L, 10L, CouponEventStatus.ACTIVE)));
        }

        private CouponLookUpService eventServiceAt(String instant) {
            return configuredService(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), 3);
        }

        private CouponEvent event(Long id, Long storeId, CouponEventStatus status) {
            CouponEvent event = BeanUtils.instantiateClass(CouponEvent.class);
            ReflectionTestUtils.setField(event, "id", id);
            ReflectionTestUtils.setField(event, "storeId", storeId);
            ReflectionTestUtils.setField(event, "campaignId", id);
            ReflectionTestUtils.setField(event, "status", status);
            ReflectionTestUtils.setField(event, "usableStartAt", TODAY.atTime(12, 30));
            ReflectionTestUtils.setField(event, "usableEndAt", TODAY.atTime(15, 0));
            return event;
        }

        private MemberInfo member(MemberStatus status) {
            return new MemberInfo(17L, "점심헌터", status, false, false, false, null, null, null, null);
        }

        private CampaignInfo campaign(Long storeId, CampaignStatus status) {
            return new CampaignInfo(storeId, 1L, storeId, status, 100, null, null, null, null,
                    null, null, 0, null, 0, null, List.of(), null, null, null, null, null);
        }

        private CouponEventRepository.InventoryQuantity quantity(Long eventId, long count) {
            var quantity = mock(CouponEventRepository.InventoryQuantity.class);
            when(quantity.getEventId()).thenReturn(eventId);
            when(quantity.getQuantity()).thenReturn(count);
            return quantity;
        }

    }

    @Nested
    @DisplayName("소진 통보 복구 조회")
    class SoldOutLookup {

        private CouponEvent event() {
            CouponEvent event = BeanUtils.instantiateClass(CouponEvent.class);
            ReflectionTestUtils.setField(event, "id", 81L);
            ReflectionTestUtils.setField(event, "campaignId", 91L);
            ReflectionTestUtils.setField(event, "businessDate", TODAY);
            return event;
        }

        @Test
        void 소진_복구는_마지막_발급_시각을_사용한다() {
            when(couponEventRepository.findById(81L)).thenReturn(java.util.Optional.of(event()));
            when(memberCouponRepository.findLastIssuedAtByCouponEventId(81L))
                    .thenReturn(java.util.Optional.of(TODAY.atTime(11, 30)));
            assertThat(service.findSoldOut(81L))
                    .contains(new CouponLookUpService.SoldOut(91L, TODAY, TODAY.atTime(11, 30)));
        }

        @Test
        void 이벤트가_없으면_복구할_소진도_없다() {
            assertThat(service.findSoldOut(81L)).isEmpty();
        }

        @Test
        void 발급_기록이_없으면_소진_시각을_만들지_않는다() {
            when(couponEventRepository.findById(81L)).thenReturn(java.util.Optional.of(event()));
            assertThat(service.findSoldOut(81L)).isEmpty();
        }

        @Test
        void 남은_재고가_있으면_소진이_아니다() {
            when(inventories.existsByCouponEventIdAndStatus(81L, com.launchcatch.coupon.entity.CouponInventoryStatus.AVAILABLE))
                    .thenReturn(true);
            assertThat(service.findSoldOut(81L)).isEmpty();
            verifyNoInteractions(couponEventRepository);
        }
    }
}
