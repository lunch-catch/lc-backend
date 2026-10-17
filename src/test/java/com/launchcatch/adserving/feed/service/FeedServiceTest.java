package com.launchcatch.adserving.feed.service;

import static com.launchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.DiscountTarget;
import com.launchcatch.adserving.engine.entity.DiscountType;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.exclusion.repository.DailyExclusionRepository;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.dto.FeedStatus;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.CategoryWishCount;
import com.launchcatch.adserving.feed.repository.FeedFilterCategoryRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import com.launchcatch.adserving.feed.repository.ImpressionCountRepository;
import com.launchcatch.adserving.feed.repository.ServeFrequencyRepository;
import com.launchcatch.adserving.feed.repository.ServeFrequencyRepository.ServedHistory;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.engine.SlotType;
import org.mockito.ArgumentCaptor;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository.Admission;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.geo.BoundingBox;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("피드 조회 서비스")
class FeedServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime SERVING_NOW = LocalDateTime.of(2026, 10, 1, 10, 15, 30, 123_000_000);
    private static final double SAVED_LAT = 37.5;
    private static final double SAVED_LNG = 127.0;

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final FeedRateLimitRepository rateLimitRepository = mock(FeedRateLimitRepository.class);
    private final AdCandidateRepository adCandidateRepository = mock(AdCandidateRepository.class);
    private final DailyExclusionRepository dailyExclusionRepository = mock(DailyExclusionRepository.class);
    private final FeedFilterCategoryRepository filterCategoryRepository =
            mock(FeedFilterCategoryRepository.class);
    private final FeedFilterRepository feedFilterRepository = mock(FeedFilterRepository.class);
    private final SwipeLogRepository swipeLogRepository = mock(SwipeLogRepository.class);
    private final ServeFrequencyRepository serveFrequencyRepository = mock(ServeFrequencyRepository.class);
    private final ImpressionCountRepository impressionCountRepository = mock(ImpressionCountRepository.class);
    private final BudgetQueryService budgetQueryService = mock(BudgetQueryService.class);
    private final ServeRecordRepository serveRecordRepository = mock(ServeRecordRepository.class);
    private final ServeLogBuffer serveLogBuffer = mock(ServeLogBuffer.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private FeedService serviceAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        return new FeedService(memberQueryService, rateLimitRepository, adCandidateRepository,
                dailyExclusionRepository, filterCategoryRepository, feedFilterRepository, swipeLogRepository,
                serveFrequencyRepository, impressionCountRepository, budgetQueryService, serveRecordRepository,
                serveLogBuffer, meterRegistry, clock);
    }

    // 넘겨받은 캠페인마다 진행률 0.5, 예약액 10만 원, 단가 10원으로 답한다. 상한은 10,500 회다.
    @SuppressWarnings("unchecked")
    private void givenBudgetForAll() {
        when(budgetQueryService.getProgress(any(), any(), any())).thenAnswer(invocation ->
                ((Collection<Long>) invocation.getArgument(0)).stream()
                        .map(id -> new BudgetProgress(id, 100_000, 10, 50_000, 100_000, 0.5))
                        .toList());
    }

    private static Instant instant(LocalDateTime time) {
        return time.atZone(ClockConfig.ZONE).toInstant();
    }

    private static OffsetDateTime offset(LocalDateTime time) {
        return time.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }

    private static MemberInfo member(boolean onboarded, Double lat, Double lng) {
        return new MemberInfo(MEMBER_ID, "점심러", MemberStatus.ACTIVE, onboarded, true, true,
                lat == null ? null : BigDecimal.valueOf(lat), lng == null ? null : BigDecimal.valueOf(lng),
                onboarded ? Gender.FEMALE : null, onboarded ? AgeGroup.AGE_30S : null);
    }

    private void givenOnboardedMemberWithSavedLocation() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true, SAVED_LAT, SAVED_LNG)));
    }

    private void givenAdmitted(LocalDateTime now) {
        when(rateLimitRepository.tryAdmit(MEMBER_ID, instant(now))).thenReturn(new Admission(true, instant(now)));
    }

    private void givenCandidates(List<AdCandidate> candidates) {
        when(adCandidateRepository.findServableWithin(eq(TODAY), any(BoundingBox.class))).thenReturn(candidates);
        when(dailyExclusionRepository.findCampaignIds(TODAY, MEMBER_ID)).thenReturn(Set.of());
        when(filterCategoryRepository.findCategoryCodes(MEMBER_ID)).thenReturn(List.of());
        when(serveFrequencyRepository.findHistory(TODAY, MEMBER_ID)).thenReturn(new ServedHistory(Map.of(), Set.of()));
        givenBudgetForAll();
    }

    private static final FeedRequest NO_LOCATION = new FeedRequest(null, null);

    @Nested
    @DisplayName("요청한 사람을 확인할 때")
    class Requester {

        @Test
        void 온보딩을_마치지_않았으면_ADSERVING_001_로_거부하고_요청_횟수에_넣지_않는다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(false, SAVED_LAT, SAVED_LNG)));

            assertThatThrownBy(() -> serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION))
                    .isInstanceOf(FeedException.class)
                    .extracting("errorCode").isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED);
            verifyNoInteractions(rateLimitRepository);
        }

        @Test
        void 회원을_찾지_못하면_온보딩_전과_같이_거부한다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION))
                    .extracting("errorCode").isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED);
        }

        @Test
        void 위치를_보내지_않았고_저장_위치도_없으면_ADSERVING_002_로_거부하고_요청_횟수에_넣지_않는다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true, null, null)));

            assertThatThrownBy(() -> serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION))
                    .extracting("errorCode").isEqualTo(FeedErrorCode.LOCATION_REQUIRED);
            verifyNoInteractions(rateLimitRepository);
        }

        @Test
        void 저장_위치가_없어도_위치를_보냈으면_그_위치로_받는다() {
            when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true, null, null)));
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(1, 37.4979, 127.0276).build()));

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, new FeedRequest(37.4979, 127.0276));

            assertThat(response.cards()).singleElement().extracting(FeedCardResponse::distanceM).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("요청 제한")
    class RateLimit {

        @Test
        void 최근_60초_6회를_넘으면_다시_요청할_수_있을_때까지_남은_초를_올림해_ADSERVING_003_로_거부한다() {
            givenOnboardedMemberWithSavedLocation();
            when(rateLimitRepository.tryAdmit(MEMBER_ID, instant(SERVING_NOW)))
                    .thenReturn(new Admission(false, instant(SERVING_NOW.plusSeconds(36).plusNanos(200_000_000))));

            assertThatThrownBy(() -> serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION))
                    .isInstanceOf(FeedRateLimitedException.class)
                    .satisfies(e -> {
                        FeedRateLimitedException limited = (FeedRateLimitedException) e;
                        assertThat(limited.getErrorCode()).isEqualTo(FeedErrorCode.RATE_LIMITED);
                        assertThat(limited.getRetryAfterSeconds()).isEqualTo(37);
                    });
            verifyNoInteractions(adCandidateRepository);
        }

        @Test
        void 들어온_요청은_다음_피드를_받을_수_있는_시각을_싣는다() {
            givenOnboardedMemberWithSavedLocation();
            LocalDateTime next = SERVING_NOW.plusSeconds(20);
            when(rateLimitRepository.tryAdmit(MEMBER_ID, instant(SERVING_NOW))).thenReturn(new Admission(true, instant(next)));
            givenCandidates(List.of());

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.nextFeedAvailableAt()).isEqualTo(offset(next));
        }
    }

    @Nested
    @DisplayName("서빙 시간대")
    class ServingHours {

        @ParameterizedTest
        @ValueSource(strings = {"09:59:59.999", "13:00:00", "00:00:00"})
        void 서빙_시간대_밖이면_후보를_찾지_않고_OUT_OF_SERVING_HOURS_다(String time) {
            LocalDateTime now = LocalDateTime.of(TODAY, LocalTime.parse(time));
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(now);

            FeedResponse response = serviceAt(now).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.status()).isEqualTo(FeedStatus.OUT_OF_SERVING_HOURS);
            assertThat(response.feedId()).isNull();
            assertThat(response.radiusM()).isNull();
            assertThat(response.cards()).isEmpty();
            assertThat(response.nextFeedAvailableAt()).isEqualTo(offset(now));
            verifyNoInteractions(adCandidateRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"10:00:00", "12:59:59.999"})
        void 서빙_시간대의_처음과_끝은_시간대_안이다(String time) {
            LocalDateTime now = LocalDateTime.of(TODAY, LocalTime.parse(time));
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(now);
            givenCandidates(List.of(candidate(1, SAVED_LAT, SAVED_LNG).build()));

            assertThat(serviceAt(now).getFeed(MEMBER_ID, NO_LOCATION).status()).isEqualTo(FeedStatus.SERVING);
        }
    }

    @Nested
    @DisplayName("서빙 시간대 안에서")
    class Serving {

        @Test
        void 후보가_없으면_NO_CANDIDATE_이고_최대_반경을_싣는다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of());

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.status()).isEqualTo(FeedStatus.NO_CANDIDATE);
            assertThat(response.feedId()).isNull();
            assertThat(response.radiusM()).isEqualTo(5000);
            assertThat(response.cards()).isEmpty();
        }

        @Test
        void 카드마다_명세의_필드를_채워_SERVING_으로_내려준다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(101, SAVED_LAT, SAVED_LNG).build(), candidate(102, SAVED_LAT, SAVED_LNG).build()));

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
            assertThat(response.feedId()).isNotNull();
            assertThat(response.feedId().version()).isEqualTo(7);
            assertThat(response.radiusM()).isEqualTo(5000);
            assertThat(response.cards()).hasSize(2);
            assertThat(response.cards()).extracting(FeedCardResponse::serveId).doesNotHaveDuplicates()
                    .allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
            FeedCardResponse card = response.cards().getFirst();
            assertThat(card.servedAt()).isEqualTo(offset(SERVING_NOW));
            assertThat(card.campaignId()).isEqualTo(101L);
            assertThat(card.storeId()).isEqualTo(1101L);
            assertThat(card.storeName()).isEqualTo("가게101");
            assertThat(card.categoryCode()).isEqualTo("KOREAN");
            assertThat(card.distanceM()).isZero();
            assertThat(card.discount()).isEqualTo(new FeedCardResponse.Discount(
                    DiscountTarget.ALL,
                    DiscountType.PERCENT, 20));
            assertThat(card.issueOpenTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(card.usableStartTime()).isEqualTo(LocalTime.of(12, 0));
            assertThat(card.usableEndTime()).isEqualTo(LocalTime.of(14, 0));
            assertThat(card.issueQuantity()).isEqualTo(30);
            assertThat(card.posterUrl()).isEqualTo("/v1/campaigns/101/poster");
        }

        @Test
        void 당일_이력과_필터_업종을_후보에서_거른다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            when(adCandidateRepository.findServableWithin(eq(TODAY), any(BoundingBox.class))).thenReturn(List.of(
                    candidate(1, SAVED_LAT, SAVED_LNG).build(),
                    candidate(2, SAVED_LAT, SAVED_LNG).build(),
                    candidate(3, SAVED_LAT, SAVED_LNG).set("categoryCode", "JAPANESE").build()));
            when(dailyExclusionRepository.findCampaignIds(TODAY, MEMBER_ID)).thenReturn(Set.of(1L));
            when(filterCategoryRepository.findCategoryCodes(MEMBER_ID)).thenReturn(List.of("KOREAN"));
            when(serveFrequencyRepository.findHistory(TODAY, MEMBER_ID)).thenReturn(new ServedHistory(Map.of(), Set.of()));
            givenBudgetForAll();

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(2L);
        }

        @Test
        void 최대_반경을_덮는_범위로_그날_후보를_읽는다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of());

            serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            verify(adCandidateRepository).findServableWithin(TODAY, BoundingBox.around(SAVED_LAT, SAVED_LNG, 5000));
        }
    }

    @Nested
    @DisplayName("배정 규칙의 입력을 모을 때")
    class Inputs {

        @Test
        void 정산에_그날_영업일과_지금_시각을_넘겨_진행률을_읽는다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(1, SAVED_LAT, SAVED_LNG).build()));

            serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            verify(budgetQueryService).getProgress(List.of(1L), TODAY, SERVING_NOW);
        }

        @Test
        void 후보가_정산이_한_번에_받는_수보다_많으면_나눠_부른다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            List<AdCandidate> many = java.util.stream.LongStream.rangeClosed(1, BudgetQueryService.MAX_CAMPAIGN_IDS + 1L)
                    .mapToObj(id -> candidate(id, SAVED_LAT, SAVED_LNG).build()).toList();
            givenCandidates(many);

            serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            verify(budgetQueryService, times(2)).getProgress(anyCollection(), eq(TODAY), eq(SERVING_NOW));
        }

        @Test
        void 정산에_그날_예산_계획이_없는_캠페인은_내려주지_않는다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(1, SAVED_LAT, SAVED_LNG).build(), candidate(2, SAVED_LAT, SAVED_LNG).build()));
            doReturn(List.of(new BudgetProgress(2L, 100_000, 10, 0, 100_000, 0.0)))
                    .when(budgetQueryService).getProgress(any(), any(), any());

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(2L);
        }

        @Test
        void 정산이_예외를_던지면_예산_규칙을_건너뛰고_서빙을_이어가며_센다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(1, SAVED_LAT, SAVED_LNG).build()));
            doThrow(new IllegalStateException("카운터 장애")).when(budgetQueryService).getProgress(any(), any(), any());

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(1L);
            assertThat(meterRegistry.counter(FeedService.BUDGET_UNAVAILABLE_METRIC).count()).isEqualTo(1);
        }

        @Test
        void 노출_상한에_닿은_캠페인은_내려주지_않는다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(1, SAVED_LAT, SAVED_LNG).build(), candidate(2, SAVED_LAT, SAVED_LNG).build()));
            // 예약액 10만 원 / 단가 10원 x 1.05 = 10,500 회
            when(impressionCountRepository.findValidCounts(eq(TODAY), any())).thenReturn(Map.of(1L, 10_500L, 2L, 10_499L));

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(2L);
        }

        @Test
        void 하루_횟수와_세션_이력을_후보에서_거른다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(1, SAVED_LAT, SAVED_LNG).build(),
                    candidate(2, SAVED_LAT, SAVED_LNG).build(), candidate(3, SAVED_LAT, SAVED_LNG).build()));
            when(serveFrequencyRepository.findHistory(TODAY, MEMBER_ID))
                    .thenReturn(new ServedHistory(Map.of(1L, 2, 2L, 1), Set.of(3L)));

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(2L);
        }

        @Test
        void 업종_선호도는_오늘을_넣은_최근_30일의_찜으로_센다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of());

            serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            verify(swipeLogRepository).countWishesByCategory(MEMBER_ID, TODAY.minusDays(29), TODAY);
        }

        @Test
        void 업종_선호도는_그_업종의_찜이_전체_찜에서_차지하는_비율이다() {
            assertThat(FeedService.categoryPreference(List.of(
                    new CategoryWishCount("KOREAN", 3), new CategoryWishCount("JAPANESE", 1))))
                    .isEqualTo(Map.of("KOREAN", 0.75, "JAPANESE", 0.25));
            assertThat(FeedService.categoryPreference(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("내려준 카드를 셀 때")
    class Recording {

        @Test
        void 응답한_카드의_캠페인을_하루와_세션_횟수에_올린다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(1, SAVED_LAT, SAVED_LNG).build(), candidate(2, SAVED_LAT, SAVED_LNG).build()));

            serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            verify(serveFrequencyRepository).recordServed(eq(TODAY), eq(MEMBER_ID),
                    org.mockito.ArgumentMatchers.argThat(ids -> Set.copyOf(ids).equals(Set.of(1L, 2L))), eq(instant(SERVING_NOW)));
        }

        @Test
        void 카드가_없는_요청도_세션을_늘린다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of());

            serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            verify(serveFrequencyRepository).recordServed(TODAY, MEMBER_ID, List.of(), instant(SERVING_NOW));
        }

        @Test
        void 서빙_시간대_밖에서는_세지_않는다() {
            LocalDateTime now = LocalDateTime.of(TODAY, LocalTime.of(9, 0));
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(now);

            serviceAt(now).getFeed(MEMBER_ID, NO_LOCATION);

            verify(serveFrequencyRepository, never()).recordServed(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("serve 기록과 서빙 로그를 남길 때")
    class ServeRecords {

        @SuppressWarnings("unchecked")
        private List<ServeRecord> savedRecords() {
            ArgumentCaptor<List<ServeRecord>> captor = ArgumentCaptor.forClass(List.class);
            verify(serveRecordRepository).saveAll(captor.capture());
            return captor.getValue();
        }

        private ServedFeed offeredFeed() {
            ArgumentCaptor<ServedFeed> captor = ArgumentCaptor.forClass(ServedFeed.class);
            verify(serveLogBuffer).offer(captor.capture());
            return captor.getValue();
        }

        @Test
        void 카드마다_응답의_serveId_로_누구에게_어느_자리로_내려줬는지_기록한다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(101, SAVED_LAT, SAVED_LNG).build(), candidate(102, SAVED_LAT, SAVED_LNG).build()));

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            List<ServeRecord> records = savedRecords();
            assertThat(records).extracting(ServeRecord::serveId)
                    .containsExactlyElementsOf(response.cards().stream().map(FeedCardResponse::serveId).toList());
            assertThat(records).extracting(ServeRecord::slotPosition).containsExactly(1, 2);
            assertThat(records).allSatisfy(r -> {
                assertThat(r.memberId()).isEqualTo(MEMBER_ID);
                assertThat(r.servedAt()).isEqualTo(instant(SERVING_NOW));
            });
            assertThat(records.getFirst().campaignId()).isEqualTo(response.cards().getFirst().campaignId());
            assertThat(records.getFirst().storeId()).isEqualTo(response.cards().getFirst().storeId());
            assertThat(records.getFirst().slotType()).isEqualTo(SlotType.ALLOCATION);
        }

        @Test
        void 응답한_피드를_같은_feedId_와_카드로_서빙_로그_대기열에_넣고_카드_수를_센다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of(candidate(101, SAVED_LAT, SAVED_LNG).build(), candidate(102, SAVED_LAT, SAVED_LNG).build()));

            FeedResponse response = serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION);

            ServedFeed feed = offeredFeed();
            assertThat(feed.feedId()).isEqualTo(response.feedId());
            assertThat(feed.businessDate()).isEqualTo(TODAY);
            assertThat(feed.memberId()).isEqualTo(MEMBER_ID);
            assertThat(feed.latitude()).isEqualTo(SAVED_LAT);
            assertThat(feed.longitude()).isEqualTo(SAVED_LNG);
            assertThat(feed.cards()).isEqualTo(savedRecords());
            assertThat(meterRegistry.counter(FeedService.SERVED_CARDS_METRIC).count()).isEqualTo(2);
        }

        @Test
        void 카드가_없어도_접속_위치는_대기열에_넣는다() {
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(SERVING_NOW);
            givenCandidates(List.of());

            serviceAt(SERVING_NOW).getFeed(MEMBER_ID, new FeedRequest(37.4979, 127.0276));

            ServedFeed feed = offeredFeed();
            assertThat(feed.feedId()).isNull();
            assertThat(feed.cards()).isEmpty();
            assertThat(feed.latitude()).isEqualTo(37.4979);
            assertThat(feed.longitude()).isEqualTo(127.0276);
        }

        @Test
        void 서빙_시간대_밖의_요청도_접속_위치는_대기열에_넣는다() {
            LocalDateTime now = LocalDateTime.of(TODAY, LocalTime.of(9, 0));
            givenOnboardedMemberWithSavedLocation();
            givenAdmitted(now);

            serviceAt(now).getFeed(MEMBER_ID, NO_LOCATION);

            assertThat(offeredFeed().cards()).isEmpty();
        }

        @Test
        void 요청_제한으로_거부된_요청은_남기지_않는다() {
            givenOnboardedMemberWithSavedLocation();
            when(rateLimitRepository.tryAdmit(MEMBER_ID, instant(SERVING_NOW)))
                    .thenReturn(new Admission(false, instant(SERVING_NOW.plusSeconds(10))));

            assertThatThrownBy(() -> serviceAt(SERVING_NOW).getFeed(MEMBER_ID, NO_LOCATION))
                    .isInstanceOf(FeedRateLimitedException.class);
            verifyNoInteractions(serveLogBuffer, serveRecordRepository);
        }
    }
}
