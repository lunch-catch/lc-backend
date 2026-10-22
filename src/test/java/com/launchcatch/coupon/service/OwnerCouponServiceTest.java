package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.coupon.contract.CouponRedeemedEvent;
import com.launchcatch.coupon.dto.CouponUseRequest;
import com.launchcatch.coupon.dto.CouponUseResponse;
import com.launchcatch.coupon.dto.OwnerCouponSummaryResponse;
import com.launchcatch.coupon.dto.OwnerCouponDailyUsageResponse;
import com.launchcatch.coupon.entity.CouponEvent;
import com.launchcatch.coupon.entity.CouponUsageHistory;
import com.launchcatch.coupon.entity.DiscountTargetType;
import com.launchcatch.coupon.entity.DiscountType;
import com.launchcatch.coupon.entity.MemberCoupon;
import com.launchcatch.coupon.entity.MemberCouponStatus;
import com.launchcatch.coupon.exception.CouponErrorCode;
import com.launchcatch.coupon.exception.CouponException;
import com.launchcatch.coupon.repository.CouponEventRepository;
import com.launchcatch.coupon.repository.CouponUsageHistoryRepository;
import com.launchcatch.coupon.repository.MemberCouponRepository.QrReference;
import com.launchcatch.coupon.repository.MemberCouponRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.exception.ErrorCode;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("점주 쿠폰 서비스")
class OwnerCouponServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);

    @Mock OwnerStatusQuery owners;
    @Mock CampaignQueryService campaigns;
    @Mock CouponEventRepository events;
    @Mock CouponUsageHistoryRepository histories;
    @Mock StoreQueryService stores;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks OwnerCouponService service;

    @Test
    void 다른_점주의_캠페인_거절은_INFO와_업무_사유로_남긴다() {
        when(owners.findCurrentStatus(7L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        when(campaigns.findAllById(List.of(501L))).thenReturn(List.of());
        try (var logs = new CouponLogCapture(OwnerCouponService.class)) {
            assertThatThrownBy(() -> service.summary(7L, 501L, DAY, DAY)).isInstanceOf(CouponException.class);

            assertThat(logs.events).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(event.getFormattedMessage()).contains("reason=CAMPAIGN_ACCESS_DENIED", "errorCode=COUPON-011");
            });
        }
    }

    @Test
    void 비활성_점주의_조회_거절은_INFO와_오류_코드로_남긴다() {
        try (var logs = new CouponLogCapture(OwnerCouponService.class)) {
            assertThatThrownBy(() -> service.summary(7L, 501L, DAY, DAY)).isInstanceOf(CouponException.class);

            assertThat(logs.events).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
                assertThat(event.getFormattedMessage()).contains("reason=OWNER_NOT_ACTIVE", "errorCode=AUTH-006");
            });
        }
    }

    @Test
    void 누적_발급_수와_사용_수를_구분해_본인_캠페인_수량을_반환한다() {
        allowOwner();
        var counts = mock(CouponEventRepository.OwnerSummary.class);
        when(counts.getTotalQuantity()).thenReturn(50L);
        when(counts.getIssuedCount()).thenReturn(24L);
        when(counts.getUsedCount()).thenReturn(12L);
        when(counts.getRemainingQuantity()).thenReturn(26L);
        when(events.findOwnerSummary(501L, DAY, DAY, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenReturn(counts);

        assertThat(service.summary(7L, 501L, DAY, DAY))
                .isEqualTo(new OwnerCouponSummaryResponse(501L, DAY, DAY, 50, 24, 12, 26));
    }

    @Test
    void 양끝을_포함해_31일을_조회할_수_있다() {
        allowOwner();
        var to = DAY.plusDays(30);
        var counts = mock(CouponEventRepository.OwnerSummary.class);
        when(events.findOwnerSummary(501L, DAY, to, DAY.atStartOfDay(), to.plusDays(1).atStartOfDay()))
                .thenReturn(counts);

        assertThat(service.summary(7L, 501L, DAY, to))
                .isEqualTo(new OwnerCouponSummaryResponse(501L, DAY, to, 0, 0, 0, 0));
    }

    @Test
    void 날짜가_없거나_뒤집혔거나_32일_이상이면_거절한다() {
        assertThatThrownBy(() -> service.summary(7L, 501L, null, DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(7L, 501L, DAY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(7L, 501L, DAY.plusDays(1), DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(7L, 501L, DAY, DAY.plusDays(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(7L, 0L, DAY, DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary(7L, 501L, LocalDate.MAX, LocalDate.MAX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OwnerAccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void 활성_상태가_아닌_점주는_거절한다(OwnerAccountStatus state) {
        when(owners.findCurrentStatus(7L)).thenReturn(Optional.of(state));

        assertThatThrownBy(() -> service.summary(7L, 501L, DAY, DAY))
                .isInstanceOfSatisfying(CouponException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED));
    }

    @Test
    void 존재하지_않는_점주도_거절한다() {
        when(owners.findCurrentStatus(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.summary(7L, 501L, DAY, DAY))
                .isInstanceOfSatisfying(CouponException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED));
    }

    @Test
    void 다른_점주의_캠페인과_없는_캠페인은_같은_오류로_거절한다() {
        when(owners.findCurrentStatus(7L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        var campaign = mock(CampaignInfo.class);
        when(campaign.ownerId()).thenReturn(8L);
        when(campaigns.findAllById(List.of(501L))).thenReturn(List.of(campaign), List.of());

        for (int request = 0; request < 2; request++) {
            assertThatThrownBy(() -> service.summary(7L, 501L, DAY, DAY))
                    .isInstanceOfSatisfying(CouponException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_ACCESS_DENIED));
        }
    }

    @Test
    void 집계나_권한_조회_실패를_수량_0으로_바꾸지_않는다() {
        allowOwner();
        var failure = new DataAccessResourceFailureException("summary unavailable");
        when(events.findOwnerSummary(501L, DAY, DAY, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                .thenThrow(failure);

        assertThatThrownBy(() -> service.summary(7L, 501L, DAY, DAY)).isSameAs(failure);

        when(owners.findCurrentStatus(7L)).thenThrow(failure);
        assertThatThrownBy(() -> service.summary(7L, 501L, DAY, DAY)).isSameAs(failure);
    }

    private void allowOwner() {
        when(owners.findCurrentStatus(7L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        var campaign = mock(CampaignInfo.class);
        when(campaign.ownerId()).thenReturn(7L);
        when(campaigns.findAllById(List.of(501L))).thenReturn(List.of(campaign));
    }

    @Nested
    @DisplayName("매장의 날짜별 쿠폰 사용 건수")
    class DailyUsageQuery {

        @Test
        void 날짜별_건수를_반환하고_사용이_없는_날은_0으로_채운다() {
            allowStore();
            var counts = List.of(count(DAY, 2), count(DAY.plusDays(2), 4));
            when(histories.findDailyUsageCounts(20L, DAY.atStartOfDay(), DAY.plusDays(3).atStartOfDay()))
                    .thenReturn(counts);

            var response = service.getDailyUsages(7L, 20L, DAY, DAY.plusDays(2));

            assertThat(response).isEqualTo(new OwnerCouponDailyUsageResponse(20L, DAY, DAY.plusDays(2), 6,
                    List.of(new OwnerCouponDailyUsageResponse.Item(DAY, 2),
                            new OwnerCouponDailyUsageResponse.Item(DAY.plusDays(1), 0),
                            new OwnerCouponDailyUsageResponse.Item(DAY.plusDays(2), 4))));
            verifyNoInteractions(campaigns);
        }

        @Test
        void 하루_조회와_이력이_없는_31일_조회도_날짜를_빠짐없이_반환한다() {
            allowStore();
            var count = count(DAY, 12);
            when(histories.findDailyUsageCounts(20L, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                    .thenReturn(List.of(count));
            when(histories.findDailyUsageCounts(20L, DAY.atStartOfDay(), DAY.plusDays(31).atStartOfDay()))
                    .thenReturn(List.of());

            assertThat(service.getDailyUsages(7L, 20L, DAY, DAY).items())
                    .containsExactly(new OwnerCouponDailyUsageResponse.Item(DAY, 12));
            var empty = service.getDailyUsages(7L, 20L, DAY, DAY.plusDays(30));
            assertThat(empty.totalUsedCount()).isZero();
            assertThat(empty.items()).hasSize(31).allSatisfy(item -> assertThat(item.usedCount()).isZero());
            assertThat(empty.items().getFirst().businessDate()).isEqualTo(DAY);
            assertThat(empty.items().getLast().businessDate()).isEqualTo(DAY.plusDays(30));
        }

        @Test
        void 잘못된_매장_ID와_날짜_범위는_조회하기_전에_거절한다() {
            assertThatThrownBy(() -> service.getDailyUsages(7L, 0L, DAY, DAY)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, null, DAY)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, DAY, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, DAY.plusDays(1), DAY)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, DAY, DAY.plusDays(31))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, LocalDate.MAX, LocalDate.MAX)).isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(histories, stores);
        }

        @ParameterizedTest
        @EnumSource(value = OwnerAccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        void 비활성_점주는_매장과_이력을_조회하지_못한다(OwnerAccountStatus state) {
            when(owners.findCurrentStatus(7L)).thenReturn(Optional.of(state));

            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, DAY, DAY))
                    .isInstanceOfSatisfying(CouponException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED));
            verifyNoInteractions(stores, histories);
        }

        @Test
        void 관리할_수_없는_매장은_이력을_조회하지_않고_거절한다() {
            when(owners.findCurrentStatus(7L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            when(stores.canManageStore(7L, 20L)).thenReturn(false);

            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, DAY, DAY))
                    .isInstanceOfSatisfying(CouponException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_ACCESS_DENIED));
            verifyNoInteractions(histories);
        }

        @Test
        void 매장_권한_조회_장애를_접근_거절이나_0건으로_바꾸지_않는다() {
            when(owners.findCurrentStatus(7L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            var failure = new DataAccessResourceFailureException("store query unavailable");
            when(stores.canManageStore(7L, 20L)).thenThrow(failure);

            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, DAY, DAY)).isSameAs(failure);
            verifyNoInteractions(histories);
        }

        @Test
        void 집계_DB_장애를_0건으로_바꾸지_않는다() {
            allowStore();
            var failure = new DataAccessResourceFailureException("usage query unavailable");
            when(histories.findDailyUsageCounts(20L, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay()))
                    .thenThrow(failure);

            assertThatThrownBy(() -> service.getDailyUsages(7L, 20L, DAY, DAY)).isSameAs(failure);
        }

        private void allowStore() {
            when(owners.findCurrentStatus(7L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
            when(stores.canManageStore(7L, 20L)).thenReturn(true);
        }

        private CouponUsageHistoryRepository.DailyUsageCount count(LocalDate day, long usedCount) {
            var count = mock(CouponUsageHistoryRepository.DailyUsageCount.class);
            when(count.getBusinessDate()).thenReturn(day);
            when(count.getUsedCount()).thenReturn(usedCount);
            return count;
        }
    }

    @Nested
    @DisplayName("매장 사용 이력 조회")
    class UsageHistoryQuery {

        private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
        private static final LocalDate TO = LocalDate.of(2026, 9, 30);
        private static final LocalDateTime USE_TIME = TO.atTime(12, 30, 0, 123456000);

        @Mock MemberCouponRepository memberCouponRepository;
        @Mock CouponEventRepository couponEventRepository;
        @Mock CouponUsageHistoryRepository repository;
        @Mock Clock clock;
        @Mock OwnerStatusQuery ownerStatusQuery;

        private OwnerCouponService service;

        @BeforeEach
        void setUp() {
            service = new OwnerCouponService(ownerStatusQuery, campaigns, couponEventRepository, memberCouponRepository,
                    repository, mock(MemberQueryService.class), stores, clock, eventPublisher);
            lenient().when(stores.canManageStore(17L, 10L)).thenReturn(true);
            when(ownerStatusQuery.findCurrentStatus(17L)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        }

        @Test
        void 현재_관리할_수_없는_매장은_이력을_읽지_않고_거절한다() {
            when(stores.canManageStore(17L, 10L)).thenReturn(false);
            assertThatThrownBy(() -> service.getUsages(17L, 10L, FROM, TO, 20, null))
                    .isInstanceOfSatisfying(CouponException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED));
            verifyNoInteractions(repository);
        }

        @Test
        void 매장_관리_권한_조회_장애를_권한_없음으로_바꾸지_않는다() {
            var failure = new DataAccessResourceFailureException("store unavailable");
            when(stores.canManageStore(17L, 10L)).thenThrow(failure);
            assertThatThrownBy(() -> service.getUsages(17L, 10L, FROM, TO, 20, null)).isSameAs(failure);
            verifyNoInteractions(repository);
        }

        @ParameterizedTest
        @EnumSource(value = OwnerAccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        void ACTIVE가_아닌_점주는_이력을_조회하지_않고_거절한다(OwnerAccountStatus status) {
            when(ownerStatusQuery.findCurrentStatus(17L)).thenReturn(Optional.of(status));

            assertThatThrownBy(() -> service.getUsages(17L, 10L, FROM, TO, 20, null))
                    .isInstanceOfSatisfying(CouponException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED));
            verifyNoInteractions(repository);
        }

        @Test
        void 점주가_없으면_이력을_조회하지_않고_거절한다() {
            when(ownerStatusQuery.findCurrentStatus(17L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getUsages(17L, 10L, FROM, TO, 20, null))
                    .isInstanceOfSatisfying(CouponException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.ROLE_NOT_ALLOWED));
            verifyNoInteractions(repository);
        }

        @Test
        void 점주_상태_조회_실패를_ACTIVE로_바꾸지_않는다() {
            var failure = new DataAccessResourceFailureException("owner status query failed");
            when(ownerStatusQuery.findCurrentStatus(17L)).thenThrow(failure);

            assertThatThrownBy(() -> service.getUsages(17L, 10L, FROM, TO, 20, null)).isSameAs(failure);
            verifyNoInteractions(repository);
        }

        @Test
        void 사용_이력이_없으면_빈_목록과_null_커서를_반환한다() {
            when(repository.findPage(eq(10L), any(), any(), any(), any(), anyInt())).thenReturn(List.of());

            assertThat(service.getUsages(17L, 10L, FROM, TO, 20, null)).isEqualTo(CursorPageResponse.of(List.of(), null));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 2700})
        void 저장된_확정_할인액과_마이크로초를_보존한_한국_사용_시각을_반환한다(int discountAmount) {
            when(repository.findPage(eq(10L), any(), any(), any(), any(), anyInt()))
                    .thenReturn(List.of(history(5001L, 1001L, discountAmount, USE_TIME)));

            var response = service.getUsages(17L, 10L, FROM, TO, 20, null);

            var item = response.items().getFirst();
            assertThat(item.usageHistoryId()).isEqualTo(5001L);
            assertThat(item.memberCouponId()).isEqualTo(1001L);
            assertThat(item.discountAmount()).isEqualTo(discountAmount);
            assertThat(item.useTime()).isEqualTo(USE_TIME.atOffset(ZoneOffset.ofHours(9)));
            assertThat(response.nextPageToken()).isNull();
        }

        @Test
        void DB_조회_실패를_빈_이력으로_바꾸지_않는다() {
            var failure = new DataAccessResourceFailureException("usage query failed");
            when(repository.findPage(eq(10L), any(), any(), any(), any(), anyInt())).thenThrow(failure);

            assertThatThrownBy(() -> service.getUsages(17L, 10L, FROM, TO, 20, null)).isSameAs(failure);
        }

        @Test
        void 다음_페이지_커서는_반환한_마지막_항목을_가리키며_같은_조건으로_재사용할_수_있다() {
            when(repository.findPage(10L, FROM.atStartOfDay(), TO.plusDays(1).atStartOfDay(), null, null, 3))
                    .thenReturn(List.of(history(5003L, 1003L, 3000, USE_TIME),
                            history(5002L, 1002L, 2000, USE_TIME), history(5001L, 1001L, 1000, USE_TIME)));
            var first = service.getUsages(17L, 10L, FROM, TO, 2, null);
            when(repository.findPage(10L, FROM.atStartOfDay(), TO.plusDays(1).atStartOfDay(), USE_TIME, 5002L, 3))
                    .thenReturn(List.of(history(5001L, 1001L, 1000, USE_TIME)));

            var second = service.getUsages(17L, 10L, FROM, TO, 2, first.nextPageToken());

            assertThat(first.items()).extracting(item -> item.usageHistoryId()).containsExactly(5003L, 5002L);
            assertThat(second.items()).extracting(item -> item.usageHistoryId()).containsExactly(5001L);
            assertThat(second.nextPageToken()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "not-a-token", "cDox", "cDoxfA"})
        void 해석할_수_없는_커서를_거절한다(String token) {
            assertThatThrownBy(() -> service.getUsages(17L, 10L, FROM, TO, 20, token))
                    .isInstanceOfSatisfying(CouponException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        }

        static Stream<Arguments> invalidCursors() {
            return Stream.of(
                    Arguments.of(0L, USE_TIME + "|10|2026-09-01|2026-09-30"),
                    Arguments.of(-1L, USE_TIME + "|10|2026-09-01|2026-09-30"),
                    Arguments.of(1L, "not-a-time|10|2026-09-01|2026-09-30"),
                    Arguments.of(1L, USE_TIME + "|11|2026-09-01|2026-09-30"),
                    Arguments.of(1L, USE_TIME + "|10|2026-09-02|2026-09-30"),
                    Arguments.of(1L, USE_TIME + "|10|2026-09-01|2026-10-01"),
                    Arguments.of(1L, USE_TIME + "|ALL"),
                    Arguments.of(1L, USE_TIME + "|10|2026-09-01|2026-09-30|extra"),
                    Arguments.of(1L, "2026-08-31T23:59|10|2026-09-01|2026-09-30"),
                    Arguments.of(1L, "2026-10-01T00:00|10|2026-09-01|2026-09-30"));
        }

        @ParameterizedTest
        @MethodSource("invalidCursors")
        void 커서_ID와_시각_및_조회_조건을_검증한다(Long id, String sortValue) {
            String token = PageTokens.encode(new PageCursor(id, sortValue));

            assertThatThrownBy(() -> service.getUsages(17L, 10L, FROM, TO, 20, token))
                    .isInstanceOfSatisfying(CouponException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        }

        static Stream<Arguments> invalidDateRanges() {
            return Stream.of(
                    Arguments.of(TO, FROM),
                    Arguments.of(FROM, LocalDate.MAX));
        }

        @ParameterizedTest
        @MethodSource("invalidDateRanges")
        void 잘못된_조회_기간을_거절한다(LocalDate from, LocalDate to) {
            assertThatThrownBy(() -> service.getUsages(17L, 10L, from, to, 20, null))
                    .isInstanceOfSatisfying(CouponException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        }

        // 매장 사용 이력 조회에 사용할 식별자와 확정 할인액, 사용 시각을 구성한다.
        private CouponUsageHistory history(Long id, Long memberCouponId, int discountAmount, LocalDateTime useTime) {
            CouponUsageHistory history = BeanUtils.instantiateClass(CouponUsageHistory.class);
            ReflectionTestUtils.setField(history, "id", id);
            ReflectionTestUtils.setField(history, "memberCouponId", memberCouponId);
            ReflectionTestUtils.setField(history, "discountAmount", discountAmount);
            ReflectionTestUtils.setField(history, "createdAt", useTime);
            return history;
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
        private OwnerCouponService service;
        private MemberCoupon coupon;

        @BeforeEach
        void setUp() {
            service = new OwnerCouponService(owners, mock(CampaignQueryService.class), events, coupons, histories,
                    members, stores, Clock.fixed(NOW.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE), eventPublisher);
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
}
