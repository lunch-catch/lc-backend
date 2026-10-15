package com.launchcatch.adserving.feed.service;

import static com.launchcatch.adserving.engine.AdCandidateFixture.candidate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.DiscountTarget;
import com.launchcatch.adserving.engine.entity.DiscountType;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.dto.FeedStatus;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.DailyExclusionRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterCategoryRepository;
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
import java.util.List;
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

    private FeedService serviceAt(LocalDateTime now) {
        Clock clock = Clock.fixed(now.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        return new FeedService(memberQueryService, rateLimitRepository, adCandidateRepository,
                dailyExclusionRepository, filterCategoryRepository, clock);
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
}
