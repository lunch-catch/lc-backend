package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.Candidate;
import com.launchcatch.adserving.engine.FeedFilter;
import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.ServedFeed;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.dto.FeedStatus;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository.Acquisition;
import com.launchcatch.adserving.feed.repository.ServeCacheRepository;
import com.launchcatch.adserving.feed.repository.ServeCacheRepository.ServeHistory;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.impression.repository.ImpressionLogRepository;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.geo.BoundingBox;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumSet;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("스와이프 피드 조회")
class FeedServiceTest {

    private static final Long MEMBER_ID = 17L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime NOW = LocalDateTime.of(TODAY, LocalTime.of(10, 15, 30, 123_000_000));
    private static final long NOW_MILLIS = NOW.atZone(ClockConfig.ZONE).toInstant().toEpochMilli();
    private static final double LATITUDE = 37.4979;
    private static final double LONGITUDE = 127.0276;
    private static final double METERS_PER_LATITUDE_DEGREE = 111_195.08;
    private static final FeedRequest HERE = new FeedRequest(LATITUDE, LONGITUDE);

    @Mock MemberQueryService memberQueryService;
    @Mock BudgetQueryService budgetQueryService;
    @Mock AdCandidateRepository adCandidateRepository;
    @Mock ImpressionLogRepository impressionLogRepository;
    @Mock SwipeLogRepository swipeLogRepository;
    @Mock FeedFilterRepository feedFilterRepository;
    @Mock FeedRateLimitRepository feedRateLimitRepository;
    @Mock ServeCacheRepository serveCacheRepository;
    @Mock ServeRecordService serveRecordService;

    private SimpleMeterRegistry meterRegistry;
    private FeedService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = serviceAt(NOW);
        lenient().when(feedRateLimitRepository.acquire(eq(MEMBER_ID), anyString(), anyLong()))
                .thenAnswer(invocation -> new Acquisition(true, invocation.getArgument(2)));
        lenient().when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(BigDecimal.ONE, BigDecimal.ONE)));
        lenient().when(feedFilterRepository.find(MEMBER_ID)).thenReturn(FeedFilter.DEFAULT);
        lenient().when(serveCacheRepository.findHistory(MEMBER_ID, TODAY)).thenReturn(ServeHistory.EMPTY);
        lenient().when(swipeLogRepository.findCampaignIdsSwipedOn(MEMBER_ID, TODAY)).thenReturn(Set.of());
        lenient().when(swipeLogRepository.countWishesByCategory(MEMBER_ID, TODAY.minusDays(29), TODAY)).thenReturn(Map.of());
        lenient().when(impressionLogRepository.countValidByCampaign(eq(TODAY), anyCollection())).thenReturn(Map.of());
        lenient().when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(NOW)))
                .thenAnswer(invocation -> progressOf(invocation.getArgument(0), 0.5));
    }

    @Nested
    @DisplayName("응답")
    class Response {

        @Test
        void 서빙_시간대에_후보가_있으면_SERVING_과_카드를_내려준다() {
            givenCandidates(candidate(101L, 420));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
            assertThat(response.feedId()).isNotNull();
            assertThat(response.radiusM()).isEqualTo(5000);
            assertThat(response.nextFeedAvailableAt()).isEqualTo(offset(NOW));
            assertThat(response.cards()).hasSize(1);
            FeedCardResponse card = response.cards().getFirst();
            assertThat(card.serveId()).isNotNull().isNotEqualTo(response.feedId());
            assertThat(card.servedAt()).isEqualTo(offset(NOW));
            assertThat(card.campaignId()).isEqualTo(101L);
            assertThat(card.storeId()).isEqualTo(1101L);
            assertThat(card.storeName()).isEqualTo("가게101");
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
        void 후보가_10장_이상인_첫_반경에서_멈추고_그_반경을_내려준다() {
            givenCandidates(candidates(10, 800));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.radiusM()).isEqualTo(1000);
            assertThat(response.cards()).hasSize(10);
        }

        @ParameterizedTest
        @ValueSource(strings = {"09:59:59.999", "13:00:00"})
        void 서빙_시간대_밖이면_후보를_찾지_않고_OUT_OF_SERVING_HOURS_다(String time) {
            LocalDateTime now = LocalDateTime.of(TODAY, LocalTime.parse(time));
            service = serviceAt(now);

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.status()).isEqualTo(FeedStatus.OUT_OF_SERVING_HOURS);
            assertThat(response.feedId()).isNull();
            assertThat(response.radiusM()).isNull();
            assertThat(response.cards()).isEmpty();
            assertThat(response.nextFeedAvailableAt()).isEqualTo(offset(now));
            verifyNoInteractions(adCandidateRepository, serveCacheRepository, serveRecordService);
        }

        @Test
        void 서빙_시간대_첫_순간에는_후보를_찾는다() {
            service = serviceAt(LocalDateTime.of(TODAY, LocalTime.of(10, 0)));
            givenCandidates();

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.status()).isEqualTo(FeedStatus.NO_CANDIDATE);
        }

        @Test
        void 후보가_없으면_가장_넓은_반경과_함께_NO_CANDIDATE_다() {
            givenCandidates();

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.status()).isEqualTo(FeedStatus.NO_CANDIDATE);
            assertThat(response.feedId()).isNull();
            assertThat(response.radiusM()).isEqualTo(5000);
            assertThat(response.cards()).isEmpty();
        }

        @Test
        void 위치를_보내지_않으면_회원의_저장_위치로_찾는다() {
            when(memberQueryService.findById(MEMBER_ID))
                    .thenReturn(Optional.of(member(BigDecimal.valueOf(LATITUDE), BigDecimal.valueOf(LONGITUDE))));
            givenCandidates(candidate(101L, 420));

            FeedResponse response = service.getFeed(MEMBER_ID, new FeedRequest(null, null));

            assertThat(response.cards()).extracting(FeedCardResponse::distanceM).containsExactly(420);
            verify(adCandidateRepository).findServable(TODAY, BoundingBox.around(LATITUDE, LONGITUDE, 5000));
        }
    }

    @Nested
    @DisplayName("오류")
    class Errors {

        @Test
        void 온보딩을_마치지_않았으면_ADSERVING_001_이고_요청_횟수를_돌려놓는다() {
            MemberInfo member = new MemberInfo(MEMBER_ID, "점심", MemberStatus.ACTIVE, false, true, true,
                    null, null, null, null);
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member));

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, HERE))
                    .isInstanceOf(FeedException.class)
                    .extracting("errorCode").isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED);
            verify(feedRateLimitRepository).release(eq(MEMBER_ID), anyString());
        }

        @Test
        void 회원을_찾지_못하면_ADSERVING_001_이다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, HERE))
                    .extracting("errorCode").isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED);
        }

        @Test
        void 위치를_보내지_않았고_저장_위치도_없으면_ADSERVING_002_이다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(null, null)));

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, new FeedRequest(null, null)))
                    .extracting("errorCode").isEqualTo(FeedErrorCode.LOCATION_REQUIRED);
            verify(feedRateLimitRepository).release(eq(MEMBER_ID), anyString());
        }

        @Test
        void 최근_60초_6회를_넘으면_ADSERVING_003_과_남은_초를_올림해_싣는다() {
            when(feedRateLimitRepository.acquire(eq(MEMBER_ID), anyString(), eq(NOW_MILLIS)))
                    .thenReturn(new Acquisition(false, NOW_MILLIS + 36_001));

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, HERE))
                    .isInstanceOf(FeedRateLimitedException.class)
                    .extracting("retryAfterSeconds").isEqualTo(37L);
            verify(feedRateLimitRepository, never()).release(anyLong(), anyString());
            verifyNoInteractions(memberQueryService);
        }

        @Test
        void 돌려놓기가_실패해도_원래_오류를_올린다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.empty());
            doThrow(new QueryTimeoutException("valkey")).when(feedRateLimitRepository).release(eq(MEMBER_ID), anyString());

            assertThatThrownBy(() -> service.getFeed(MEMBER_ID, HERE))
                    .extracting("errorCode").isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED);
        }
    }

    @Nested
    @DisplayName("요청 제한")
    class RateLimit {

        @Test
        void 이번_요청으로_한도에_닿으면_가장_오래된_요청이_창을_벗어나는_시각을_내려준다() {
            when(feedRateLimitRepository.acquire(eq(MEMBER_ID), anyString(), eq(NOW_MILLIS)))
                    .thenReturn(new Acquisition(true, NOW_MILLIS + 42_000));
            givenCandidates();

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.nextFeedAvailableAt()).isEqualTo(offset(NOW.plusSeconds(42)));
        }

        @Test
        void 저장소가_멈추면_통과시키고_통과시킨_횟수를_센다() {
            when(feedRateLimitRepository.acquire(eq(MEMBER_ID), anyString(), eq(NOW_MILLIS)))
                    .thenThrow(new QueryTimeoutException("valkey"));
            givenCandidates(candidate(101L, 420));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
            assertThat(response.nextFeedAvailableAt()).isEqualTo(offset(NOW));
            assertThat(fallbackCount("rate_limit")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("후보 조건")
    class Eligibility {

        @Test
        void 예산_소진_진행률이_1_2_를_넘거나_노출_상한에_닿은_캠페인을_뺀다() {
            givenCandidates(candidate(101L, 100), candidate(102L, 200), candidate(103L, 300));
            when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(NOW))).thenReturn(List.of(
                    progress(101L, 1.21), progress(102L, 1.2), progress(103L, 0.1)));
            when(impressionLogRepository.countValidByCampaign(eq(TODAY), anyCollection()))
                    .thenReturn(Map.of(103L, 1050L));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(102L);
        }

        @Test
        void 그날_예산_계획이_없는_캠페인을_뺀다() {
            givenCandidates(candidate(101L, 100), candidate(102L, 200));
            when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(NOW)))
                    .thenReturn(List.of(progress(102L, 0.5)));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(102L);
        }

        @Test
        void 임시_예산을_읽지_못하면_예산_조건_없이_내려주고_센다() {
            givenCandidates(candidate(101L, 100), candidate(102L, 200));
            when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(NOW)))
                    .thenThrow(new IllegalStateException("counter down"));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactlyInAnyOrder(101L, 102L);
            assertThat(fallbackCount("budget")).isEqualTo(1);
            verifyNoInteractions(impressionLogRepository);
        }

        @Test
        void 임시_정산이_구현을_내놓기_전에는_예산_조건_없이_내려주고_센다() {
            service = serviceAt(NOW, new StaticListableBeanFactory().getBeanProvider(BudgetQueryService.class));
            givenCandidates(candidate(101L, 100));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(101L);
            assertThat(fallbackCount("budget")).isEqualTo(1);
        }

        @Test
        void 예산은_조건을_통과한_후보만_묻고_후보가_없으면_묻지_않는다() {
            givenCandidates(candidate(101L, 100).withGender(TargetGender.MALE));

            service.getFeed(MEMBER_ID, HERE);

            verifyNoInteractions(budgetQueryService, impressionLogRepository);
        }

        @Test
        void 대상_성별이나_연령대가_맞지_않으면_뺀다() {
            givenCandidates(
                    candidate(101L, 100).withGender(TargetGender.MALE),
                    candidate(102L, 200).withAgeGroups(Set.of(AgeGroup.AGE_30S)),
                    candidate(103L, 300).withGender(TargetGender.FEMALE).withAgeGroups(Set.of(AgeGroup.AGE_20S)));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(103L);
        }

        @Test
        void 피드_필터의_업종에_없는_캠페인을_뺀다() {
            when(feedFilterRepository.find(MEMBER_ID)).thenReturn(new FeedFilter(FeedSort.DISTANCE, Set.of("JAPANESE")));
            givenCandidates(candidate(101L, 100), candidate(102L, 200).withCategory("JAPANESE"));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(102L);
        }

        @Test
        void 오늘_찜하거나_패스한_캠페인을_뺀다() {
            when(swipeLogRepository.findCampaignIdsSwipedOn(MEMBER_ID, TODAY)).thenReturn(Set.of(101L));
            givenCandidates(candidate(101L, 100), candidate(102L, 200));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(102L);
        }

        @Test
        void 오늘_2회_받았거나_이번_세션에_받은_캠페인을_뺀다() {
            when(serveCacheRepository.findHistory(MEMBER_ID, TODAY))
                    .thenReturn(new ServeHistory(Map.of(101L, 2L, 102L, 1L, 103L, 1L), Set.of(103L)));
            givenCandidates(candidate(101L, 100), candidate(102L, 200), candidate(103L, 300));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(102L);
        }

        @Test
        void 당일_이력을_읽지_못하면_이력_조건_없이_내려주고_센다() {
            when(serveCacheRepository.findHistory(MEMBER_ID, TODAY)).thenThrow(new QueryTimeoutException("valkey"));
            givenCandidates(candidate(101L, 100));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(101L);
            assertThat(fallbackCount("serve_history")).isEqualTo(1);
        }

        @Test
        void 임시_캠페인이_고른_노출_반경_밖이면_뺀다() {
            givenCandidates(candidate(101L, 600).withTargetRadius(500), candidate(102L, 400).withTargetRadius(500));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(102L);
        }

        @Test
        void 관련성_점수의_카테고리_선호도는_최근_30일_업종별_찜_수로_매긴다() {
            when(swipeLogRepository.countWishesByCategory(MEMBER_ID, TODAY.minusDays(29), TODAY))
                    .thenReturn(Map.of("JAPANESE", 5L, "KOREAN", 0L));
            List<TestCandidate> candidates = new java.util.ArrayList<>(
                    java.util.stream.IntStream.range(0, 10).mapToObj(i -> candidate(101L + i, 100 + i * 10)).toList());
            candidates.add(candidate(111L, 900).withCategory("JAPANESE"));
            givenCandidates(candidates);

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).contains(111L).doesNotContain(110L);
        }
    }

    @Nested
    @DisplayName("기록")
    class Recording {

        @Test
        void 내려준_카드를_serve_기록과_서빙_로그로_남긴다() {
            givenCandidates(candidate(101L, 100), candidate(102L, 200));

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            ArgumentCaptor<ServedFeed> captor = ArgumentCaptor.forClass(ServedFeed.class);
            verify(serveCacheRepository).record(captor.capture());
            ServedFeed served = captor.getValue();
            assertThat(served.feedId()).isEqualTo(response.feedId());
            assertThat(served.memberId()).isEqualTo(MEMBER_ID);
            assertThat(served.businessDate()).isEqualTo(TODAY);
            assertThat(served.servedAt()).isEqualTo(NOW);
            assertThat(served.latitude()).isEqualTo(LATITUDE);
            assertThat(served.longitude()).isEqualTo(LONGITUDE);
            assertThat(served.cards()).extracting(ServedFeed.ServedCard::serveId)
                    .containsExactlyElementsOf(response.cards().stream().map(FeedCardResponse::serveId).toList());
            verify(serveRecordService).recordAsync(served);
        }

        @Test
        void 후보가_없어도_그날_접속_위치를_남긴다() {
            givenCandidates();

            service.getFeed(MEMBER_ID, HERE);

            ArgumentCaptor<ServedFeed> captor = ArgumentCaptor.forClass(ServedFeed.class);
            verify(serveRecordService).recordAsync(captor.capture());
            assertThat(captor.getValue().cards()).isEmpty();
        }

        @Test
        void serve_기록을_쓰지_못해도_피드를_내려주고_센다() {
            givenCandidates(candidate(101L, 100));
            doThrow(new QueryTimeoutException("valkey")).when(serveCacheRepository).record(any());

            FeedResponse response = service.getFeed(MEMBER_ID, HERE);

            assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
            assertThat(fallbackCount("serve_cache")).isEqualTo(1);
            verify(serveRecordService).recordAsync(any());
        }
    }

    private FeedService serviceAt(LocalDateTime now) {
        return serviceAt(now, new StaticListableBeanFactory(Map.of("budgetQueryService", budgetQueryService))
                .getBeanProvider(BudgetQueryService.class));
    }

    private FeedService serviceAt(LocalDateTime now, ObjectProvider<BudgetQueryService> budget) {
        Clock clock = Clock.fixed(now.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        return new FeedService(memberQueryService, budget, adCandidateRepository, impressionLogRepository,
                swipeLogRepository, feedFilterRepository, feedRateLimitRepository, serveCacheRepository,
                serveRecordService, meterRegistry, clock);
    }

    private double fallbackCount(String target) {
        return meterRegistry.counter(FeedService.FALLBACK_METRIC, "target", target).count();
    }

    private void givenCandidates(TestCandidate... candidates) {
        givenCandidates(List.of(candidates));
    }

    private void givenCandidates(List<TestCandidate> candidates) {
        lenient().when(adCandidateRepository.findServable(eq(TODAY), any()))
                .thenReturn(candidates.stream().map(TestCandidate::build).toList());
    }

    private static List<TestCandidate> candidates(int count, int maxDistance) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> candidate(201L + i, maxDistance - i * 10))
                .toList();
    }

    private static TestCandidate candidate(Long campaignId, int metersNorth) {
        return new TestCandidate(campaignId, metersNorth, 3000, TargetGender.ALL, EnumSet.allOf(AgeGroup.class), "KOREAN");
    }

    private static MemberInfo member(BigDecimal latitude, BigDecimal longitude) {
        return new MemberInfo(MEMBER_ID, "점심", MemberStatus.ACTIVE, true, true, true,
                latitude, longitude, Gender.FEMALE, com.launchcatch.member.contract.AgeGroup.AGE_20S);
    }

    private static List<BudgetProgress> progressOf(Collection<Long> campaignIds, double rate) {
        return campaignIds.stream().map(id -> progress(id, rate)).toList();
    }

    // 예약액 10000 원, 단가 10 원이라 노출 상한은 1050 회다
    private static BudgetProgress progress(Long campaignId, double rate) {
        return new BudgetProgress(campaignId, 10_000, 10, 0, 0, rate);
    }

    private static OffsetDateTime offset(LocalDateTime time) {
        return time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }

    private record TestCandidate(Long campaignId, int metersNorth, int targetRadius, TargetGender gender,
                                 Set<AgeGroup> ageGroups, String category) {

        TestCandidate withGender(TargetGender value) {
            return new TestCandidate(campaignId, metersNorth, targetRadius, value, ageGroups, category);
        }

        TestCandidate withAgeGroups(Set<AgeGroup> value) {
            return new TestCandidate(campaignId, metersNorth, targetRadius, gender, value, category);
        }

        TestCandidate withCategory(String value) {
            return new TestCandidate(campaignId, metersNorth, targetRadius, gender, ageGroups, value);
        }

        TestCandidate withTargetRadius(int value) {
            return new TestCandidate(campaignId, metersNorth, value, gender, ageGroups, category);
        }

        Candidate build() {
            return new Candidate(campaignId, campaignId + 1000, "가게" + campaignId, category,
                    LATITUDE + metersNorth / METERS_PER_LATITUDE_DEGREE, LONGITUDE, targetRadius, gender, ageGroups,
                    DiscountTargetType.ALL, DiscountType.PERCENT, 20, LocalTime.of(12, 0), LocalTime.of(14, 0), 30,
                    LocalDateTime.of(2026, 9, 1, 9, 0), null);
        }
    }
}
