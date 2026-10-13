package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.SwipeHistory;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.dto.FeedStatus;
import com.launchcatch.adserving.feed.entity.SwipeAction;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import com.launchcatch.adserving.feed.repository.FeedRequestLimitRepository;
import com.launchcatch.adserving.feed.repository.FeedRequestPermit;
import com.launchcatch.adserving.feed.repository.ServedHistory;
import com.launchcatch.adserving.feed.repository.ServedHistoryRepository;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("피드 조회 서비스")
class FeedServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 1, 10, 15, 30, 123_000_000, ZoneOffset.ofHours(9));
    private static final double SAVED_LATITUDE = 37.5;
    private static final double SAVED_LONGITUDE = 127.0;

    // 위도 1도는 약 111,195m 라 이 값을 곱하면 북쪽으로 대략 그 미터만큼 떨어진 위도가 된다
    private static final double DEGREE_PER_METER = 1 / 111_195.0;

    @Mock MemberQueryService memberQueryService;
    @Mock ObjectProvider<BudgetQueryService> budgetQueryServiceProvider;
    @Mock BudgetQueryService budgetQueryService;
    @Mock AdCandidateRepository adCandidateRepository;
    @Mock FeedFilterRepository feedFilterRepository;
    @Mock SwipeLogRepository swipeLogRepository;
    @Mock ImpressionLogRepository impressionLogRepository;
    @Mock FeedRequestLimitRepository feedRequestLimitRepository;
    @Mock ServedHistoryRepository servedHistoryRepository;
    @Mock ServeRecordService serveRecordService;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private FeedService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(NOW);
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true, SAVED_LATITUDE, SAVED_LONGITUDE)));
        when(feedRequestLimitRepository.tryAcquire(eq(MEMBER_ID), any()))
                .thenAnswer(invocation -> new FeedRequestPermit(true, invocation.getArgument(1)));
        when(feedFilterRepository.findSort(MEMBER_ID)).thenReturn(Optional.empty());
        when(feedFilterRepository.findCategories(MEMBER_ID)).thenReturn(List.of());
        when(swipeLogRepository.findByMemberSince(eq(MEMBER_ID), any())).thenReturn(List.of());
        when(servedHistoryRepository.find(MEMBER_ID, TODAY)).thenReturn(ServedHistory.EMPTY);
        when(budgetQueryServiceProvider.getIfAvailable()).thenReturn(null);
    }

    @Nested
    @DisplayName("요청한 사용자를 확인한다")
    class Viewer {

        @Test
        void 온보딩을_마치지_않았으면_ADSERVING_001_이고_요청_횟수에_넣지_않는다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(false, SAVED_LATITUDE, SAVED_LONGITUDE)));

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, null, null))
                    .isInstanceOf(FeedException.class)
                    .extracting(e -> ((FeedException) e).getErrorCode())
                    .isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED);
            verifyNoInteractions(feedRequestLimitRepository);
        }

        @Test
        void 위치를_보내지_않았고_저장_위치도_없으면_ADSERVING_002_이고_요청_횟수에_넣지_않는다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true, null, null)));

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, null, null))
                    .isInstanceOf(FeedException.class)
                    .extracting(e -> ((FeedException) e).getErrorCode())
                    .isEqualTo(FeedErrorCode.LOCATION_REQUIRED);
            verifyNoInteractions(feedRequestLimitRepository);
        }

        @Test
        void 임시_위치_동의를_보지_않는다() {
            MemberInfo optedOut = new MemberInfo(MEMBER_ID, "닉네임", MemberStatus.ACTIVE, true, false, false,
                    BigDecimal.valueOf(SAVED_LATITUDE), BigDecimal.valueOf(SAVED_LONGITUDE), Gender.FEMALE,
                    com.launchcatch.member.contract.AgeGroup.AGE_30S);
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(optedOut));
            givenCandidates(candidate(101, 420));

            assertThat(service.getFeed(MEMBER_ID, null, null).status()).isEqualTo(FeedStatus.SERVING);
        }

        @Test
        void 위치를_보내지_않으면_저장_위치_기준으로_거리를_잰다() {
            givenCandidates(candidate(101, 420));

            FeedResponse response = service.getFeed(MEMBER_ID, null, null);

            assertThat(response.cards().getFirst().distanceM()).isEqualTo(420);
        }

        @Test
        void 보낸_위치가_저장_위치보다_우선한다() {
            givenCandidates(candidate(101, 420));
            double requestLatitude = SAVED_LATITUDE + 100 * DEGREE_PER_METER;

            FeedResponse response = service.getFeed(MEMBER_ID, requestLatitude, SAVED_LONGITUDE);

            assertThat(response.cards().getFirst().distanceM()).isEqualTo(320);
        }
    }

    @Nested
    @DisplayName("요청 제한")
    class RateLimit {

        @Test
        void 최근_60초_6회를_넘으면_다시_요청할_수_있을_때까지_남은_초를_올림해_싣는다() {
            when(feedRequestLimitRepository.tryAcquire(eq(MEMBER_ID), any()))
                    .thenReturn(new FeedRequestPermit(false, NOW.toInstant().plusMillis(36_200)));

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, null, null))
                    .isInstanceOf(FeedRateLimitedException.class)
                    .extracting(e -> ((FeedRateLimitedException) e).getRetryAfterSeconds())
                    .isEqualTo(37L);
            verifyNoInteractions(adCandidateRepository);
        }

        @Test
        void 다음_피드_가능_시각은_요청_제한의_판정을_그대로_준다() {
            when(feedRequestLimitRepository.tryAcquire(eq(MEMBER_ID), any()))
                    .thenReturn(new FeedRequestPermit(true, NOW.toInstant().plusSeconds(37)));

            FeedResponse response = service.getFeed(MEMBER_ID, null, null);

            assertThat(response.nextFeedAvailableAt()).isEqualTo(NOW.plusSeconds(37));
        }

        @Test
        void 요청_제한_캐시가_멈추면_통과시키고_센다() {
            when(feedRequestLimitRepository.tryAcquire(eq(MEMBER_ID), any())).thenThrow(new QueryTimeoutException("down"));

            FeedResponse response = service.getFeed(MEMBER_ID, null, null);

            assertThat(response.nextFeedAvailableAt()).isEqualTo(NOW);
            assertThat(bypassCount("rate_limit")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("서빙 시간대")
    class ServingHours {

        @ParameterizedTest
        @ValueSource(strings = {"09:59:59.999", "13:00:00", "23:00:00"})
        void 서빙_시간대_밖이면_OUT_OF_SERVING_HOURS_이고_feedId_와_반경이_없다(String time) {
            FeedResponse response = serviceAt(TODAY, time).getFeed(MEMBER_ID, null, null);

            assertThat(response.status()).isEqualTo(FeedStatus.OUT_OF_SERVING_HOURS);
            assertThat(response.feedId()).isNull();
            assertThat(response.radiusM()).isNull();
            assertThat(response.cards()).isEmpty();
            assertThat(response.nextFeedAvailableAt()).isNotNull();
            verifyNoInteractions(adCandidateRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"10:00:00", "12:59:59.999"})
        void 서빙_시간대_경계_안쪽은_서빙한다(String time) {
            givenCandidates(candidate(101, 420));

            FeedResponse response = serviceAt(TODAY, time).getFeed(MEMBER_ID, null, null);

            assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
        }

        @Test
        void 서빙_시간대_밖이어도_요청_횟수에_넣는다() {
            serviceAt(TODAY, "09:00:00").getFeed(MEMBER_ID, null, null);

            verify(feedRequestLimitRepository).tryAcquire(eq(MEMBER_ID), any());
        }
    }

    @Nested
    @DisplayName("응답")
    class Response {

        @Test
        void 후보가_없으면_NO_CANDIDATE_이고_가장_큰_탐색_반경을_준다() {
            givenCandidates();

            FeedResponse response = service.getFeed(MEMBER_ID, null, null);

            assertThat(response.status()).isEqualTo(FeedStatus.NO_CANDIDATE);
            assertThat(response.feedId()).isNull();
            assertThat(response.radiusM()).isEqualTo(5000);
            assertThat(response.cards()).isEmpty();
            verify(serveRecordService, never()).record(any());
        }

        @Test
        void 카드의_필드가_명세와_같다() {
            givenCandidates(candidate(101, 420));

            FeedResponse response = service.getFeed(MEMBER_ID, null, null);

            assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
            assertThat(response.feedId()).isNotNull();
            assertThat(response.radiusM()).isEqualTo(5000);
            FeedCardResponse card = response.cards().getFirst();
            assertThat(card.serveId()).isNotNull();
            assertThat(card.servedAt()).isEqualTo(NOW);
            assertThat(card.campaignId()).isEqualTo(101L);
            assertThat(card.storeId()).isEqualTo(55L);
            assertThat(card.storeName()).isEqualTo("점심식당");
            assertThat(card.categoryCode()).isEqualTo("KOREAN");
            assertThat(card.distanceM()).isEqualTo(420);
            assertThat(card.discount()).isEqualTo(
                    new FeedCardResponse.Discount(DiscountTargetType.ALL, DiscountType.PERCENT, 20));
            assertThat(card.issueOpenTime()).isEqualTo("11:00");
            assertThat(card.usableStartTime()).isEqualTo("12:00");
            assertThat(card.usableEndTime()).isEqualTo("14:00");
            assertThat(card.issueQuantity()).isEqualTo(30);
            assertThat(card.posterUrl()).isEqualTo("/v1/campaigns/101/poster");
        }

        @Test
        void 피드와_카드의_식별자는_UUIDv7_이다() {
            givenCandidates(candidate(101, 420), candidate(102, 430));

            FeedResponse response = service.getFeed(MEMBER_ID, null, null);

            assertThat(response.feedId().version()).isEqualTo(7);
            assertThat(response.cards()).extracting(card -> card.serveId().version()).containsOnly(7);
            assertThat(response.cards()).extracting(FeedCardResponse::serveId).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("사용자 이력과 필터")
    class History {

        @Test
        void 그날_찜하거나_패스한_캠페인은_빼고_전날_것은_빼지_않는다() {
            givenCandidates(candidate(101, 100), candidate(102, 100), candidate(103, 100));
            when(swipeLogRepository.findByMemberSince(MEMBER_ID, TODAY.minusDays(29))).thenReturn(List.of(
                    new SwipeHistory(TODAY, 101, "KOREAN", SwipeAction.WISH),
                    new SwipeHistory(TODAY, 102, "KOREAN", SwipeAction.PASS),
                    new SwipeHistory(TODAY.minusDays(1), 103, "KOREAN", SwipeAction.PASS)));

            assertThat(campaignIds(service.getFeed(MEMBER_ID, null, null))).containsExactly(103L);
        }

        @Test
        void 그날_2회를_채운_캠페인과_이번_세션에_내려준_캠페인을_뺀다() {
            givenCandidates(candidate(101, 100), candidate(102, 100), candidate(103, 100));
            when(servedHistoryRepository.find(MEMBER_ID, TODAY))
                    .thenReturn(new ServedHistory(Map.of(101L, 2, 103L, 1), Set.of(102L)));

            assertThat(campaignIds(service.getFeed(MEMBER_ID, null, null))).containsExactly(103L);
        }

        @Test
        void 내려준_기록_캐시가_멈추면_기록_없이_배정하고_센다() {
            givenCandidates(candidate(101, 100));
            when(servedHistoryRepository.find(MEMBER_ID, TODAY)).thenThrow(new QueryTimeoutException("down"));

            assertThat(campaignIds(service.getFeed(MEMBER_ID, null, null))).containsExactly(101L);
            assertThat(bypassCount("served_history")).isEqualTo(1.0);
        }

        @Test
        void 피드_필터의_업종으로_거른다() {
            givenCandidates(candidate(101, 100), candidate(102, 100).withCategory("JAPANESE"));
            when(feedFilterRepository.findSort(MEMBER_ID)).thenReturn(Optional.of(FeedSort.DISCOUNT_RATE));
            when(feedFilterRepository.findCategories(MEMBER_ID)).thenReturn(List.of("JAPANESE"));

            assertThat(campaignIds(service.getFeed(MEMBER_ID, null, null))).containsExactly(102L);
        }
    }

    @Nested
    @DisplayName("예산")
    class Budget {

        @BeforeEach
        void setUpBudget() {
            when(budgetQueryServiceProvider.getIfAvailable()).thenReturn(budgetQueryService);
        }

        @Test
        void 진행률과_유효_노출_수로_거른다() {
            givenCandidates(candidate(101, 100), candidate(102, 100), candidate(103, 100));
            when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(NOW.toLocalDateTime())))
                    .thenReturn(List.of(progress(101, 1.3), progress(102, 0.5), progress(103, 0.5)));
            when(impressionLogRepository.countValidByCampaign(eq(TODAY), anyCollection()))
                    .thenReturn(Map.of(102L, 105L));

            assertThat(campaignIds(service.getFeed(MEMBER_ID, null, null))).containsExactly(103L);
        }

        @Test
        void 예산_조회가_실패하면_피드도_실패한다() {
            givenCandidates(candidate(101, 100));
            when(budgetQueryService.getProgress(anyCollection(), any(), any()))
                    .thenThrow(new IllegalStateException("counter down"));

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, null, null)).isInstanceOf(IllegalStateException.class);
            verify(serveRecordService, never()).record(any());
        }

        @Test
        void 임시_예산_조회_구현이_없으면_예산_조건_없이_내려준다() {
            when(budgetQueryServiceProvider.getIfAvailable()).thenReturn(null);
            givenCandidates(candidate(101, 100));

            assertThat(campaignIds(service.getFeed(MEMBER_ID, null, null))).containsExactly(101L);
            verifyNoInteractions(impressionLogRepository);
        }
    }

    @Nested
    @DisplayName("기록")
    class Recording {

        @Test
        void 내려준_카드를_serve_기록으로_남긴다() {
            givenCandidates(candidate(101, 100), candidate(102, 200));

            FeedResponse response = service.getFeed(MEMBER_ID, null, null);

            ArgumentCaptor<List<ServeRecord>> captor = ArgumentCaptor.captor();
            verify(serveRecordService).record(captor.capture());
            List<ServeRecord> records = captor.getValue();
            assertThat(records).extracting(ServeRecord::serveId)
                    .containsExactlyElementsOf(response.cards().stream().map(FeedCardResponse::serveId).toList());
            assertThat(records).extracting(ServeRecord::feedId).containsOnly(response.feedId());
            assertThat(records).extracting(ServeRecord::memberId).containsOnly(MEMBER_ID);
            assertThat(records).extracting(ServeRecord::businessDate).containsOnly(TODAY);
            assertThat(records).extracting(ServeRecord::servedAt).containsOnly(NOW.toLocalDateTime());
            assertThat(records).extracting(ServeRecord::slotType)
                    .containsExactly(SlotType.ALLOCATION, SlotType.ALLOCATION);
            assertThat(records).extracting(ServeRecord::slotPosition).containsExactly(1, 2);
        }

        @Test
        void 통과한_요청은_그날_접속_위치를_남긴다() {
            double latitude = 37.4979;
            double longitude = 127.0276;

            service.getFeed(MEMBER_ID, latitude, longitude);

            verify(serveRecordService).recordVisit(MEMBER_ID, TODAY, latitude, longitude);
        }

        @Test
        void 서빙_시간대_밖에도_그날_접속_위치를_남긴다() {
            serviceAt(TODAY, "09:00:00").getFeed(MEMBER_ID, null, null);

            verify(serveRecordService).recordVisit(MEMBER_ID, TODAY, SAVED_LATITUDE, SAVED_LONGITUDE);
        }
    }

    private FeedService serviceAt(LocalDate date, String time) {
        return serviceAt(LocalDateTime.of(date, LocalTime.parse(time)).atZone(ClockConfig.ZONE).toOffsetDateTime());
    }

    private FeedService serviceAt(OffsetDateTime now) {
        Clock clock = Clock.fixed(now.toInstant(), ClockConfig.ZONE);
        return new FeedService(memberQueryService, budgetQueryServiceProvider, adCandidateRepository,
                feedFilterRepository, swipeLogRepository, impressionLogRepository, feedRequestLimitRepository,
                servedHistoryRepository, serveRecordService, clock, meterRegistry);
    }

    private double bypassCount(String target) {
        return meterRegistry.counter(FeedService.CACHE_BYPASS_METRIC, "target", target).count();
    }

    private void givenCandidates(TestCandidate... candidates) {
        when(adCandidateRepository.findServable(eq(TODAY), any()))
                .thenReturn(java.util.Arrays.stream(candidates).map(TestCandidate::build).toList());
    }

    private static List<Long> campaignIds(FeedResponse response) {
        return response.cards().stream().map(FeedCardResponse::campaignId).toList();
    }

    private static MemberInfo member(boolean onboarded, Double latitude, Double longitude) {
        return new MemberInfo(MEMBER_ID, "닉네임", MemberStatus.ACTIVE, onboarded, false, true,
                latitude == null ? null : BigDecimal.valueOf(latitude),
                longitude == null ? null : BigDecimal.valueOf(longitude),
                onboarded ? Gender.FEMALE : null,
                onboarded ? com.launchcatch.member.contract.AgeGroup.AGE_30S : null);
    }

    private static BudgetProgress progress(long campaignId, double rate) {
        return new BudgetProgress(campaignId, 1000, 10, 0, 0, rate);
    }

    private static TestCandidate candidate(long campaignId, int metersNorth) {
        return new TestCandidate(campaignId, metersNorth, "KOREAN");
    }

    // 테스트에서 바꾸는 값만 열어 둔 후보 픽스처다
    private record TestCandidate(long campaignId, int metersNorth, String category) {

        TestCandidate withCategory(String value) {
            return new TestCandidate(campaignId, metersNorth, value);
        }

        FeedCandidate build() {
            return new FeedCandidate(campaignId, 55, "점심식당", category,
                    SAVED_LATITUDE + metersNorth * DEGREE_PER_METER, SAVED_LONGITUDE, 1000,
                    TargetGender.ALL, Set.of(AgeGroup.AGE_30S), DiscountTargetType.ALL, DiscountType.PERCENT, 20,
                    LocalTime.of(12, 0), LocalTime.of(14, 0), 30,
                    LocalDateTime.of(2026, 9, 1, 0, 0).plusMinutes(campaignId), null);
        }
    }
}
