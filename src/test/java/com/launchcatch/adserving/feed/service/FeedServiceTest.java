package com.launchcatch.adserving.feed.service;

import static com.launchcatch.adserving.engine.entity.AdCandidateFixture.DATE;
import static com.launchcatch.adserving.engine.entity.AdCandidateFixture.campaign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.TargetGender;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedLocationRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.dto.FeedStatus;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository.FeedFilterRow;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository.Decision;
import com.launchcatch.adserving.feed.repository.ServeLogRepository.ServeLogRow;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository.ServeHistory;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository.ServeRecord;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.impression.repository.ImpressionCountRepository;
import com.launchcatch.adserving.wishlist.repository.WishlistRepository;
import com.launchcatch.billing.contract.BudgetProgress;
import com.launchcatch.billing.contract.BudgetQueryService;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.contract.MemberStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
@DisplayName("스와이프 피드 조회")
class FeedServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final Instant NOW = Instant.parse("2026-10-01T01:15:30.123Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.of(2026, 10, 1, 10, 15, 30, 123_000_000);
    private static final FeedLocationRequest HERE = new FeedLocationRequest(37.4979, 127.0276);
    private static final FeedLocationRequest NO_LOCATION = new FeedLocationRequest(null, null);

    @Mock FeedRateLimitRepository rateLimitRepository;
    @Mock ServeRecordRepository serveRecordRepository;
    @Mock AdCandidateRepository adCandidateRepository;
    @Mock FeedFilterRepository feedFilterRepository;
    @Mock SwipeLogRepository swipeLogRepository;
    @Mock WishlistRepository wishlistRepository;
    @Mock ImpressionCountRepository impressionCountRepository;
    @Mock MemberQueryService memberQueryService;
    @Mock BudgetQueryService budgetQueryService;
    @Mock FeedLogWriter feedLogWriter;

    private SimpleMeterRegistry meterRegistry;
    private FeedService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = serviceAt(NOW);
        lenient().when(rateLimitRepository.acquire(eq(MEMBER_ID), anyString(), any()))
                .thenAnswer(invocation -> new Decision(true, invocation.getArgument(2)));
        lenient().when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true, null, null)));
        lenient().when(feedFilterRepository.findByMemberId(MEMBER_ID))
                .thenReturn(new FeedFilterRow(Set.of(), FeedSort.DISTANCE));
        lenient().when(serveRecordRepository.findHistory(DATE, MEMBER_ID))
                .thenReturn(new ServeHistory(Map.of(), Set.of()));
        lenient().when(budgetQueryService.getProgress(any(), eq(DATE), any()))
                .thenAnswer(invocation -> budgets(invocation.getArgument(0)));
    }

    private FeedService serviceAt(Instant now) {
        return new FeedService(Clock.fixed(now, ClockConfig.ZONE), rateLimitRepository, serveRecordRepository,
                adCandidateRepository, feedFilterRepository, swipeLogRepository, wishlistRepository,
                impressionCountRepository, memberQueryService, budgetQueryService, feedLogWriter, meterRegistry);
    }

    private static MemberInfo member(boolean onboarded, BigDecimal latitude, BigDecimal longitude) {
        return new MemberInfo(MEMBER_ID, "점심러", MemberStatus.ACTIVE, onboarded, true, true, latitude, longitude,
                onboarded ? Gender.FEMALE : null, onboarded ? AgeGroup.AGE_30S : null);
    }

    // 넘겨받은 캠페인 모두 예약액 10,000원, 단가 10원, 진행률 0.5 다. 노출 상한은 1,050회다
    private static List<BudgetProgress> budgets(Collection<Long> campaignIds) {
        return campaignIds.stream().map(id -> new BudgetProgress(id, 10_000, 10, 5_000, 10_000, 0.5)).toList();
    }

    private void candidates(AdCandidate... candidates) {
        when(adCandidateRepository.findServableIn(eq(DATE), any(), any(), any(), any())).thenReturn(List.of(candidates));
    }

    private static List<Long> campaignIds(FeedResponse response) {
        return response.cards().stream().map(FeedCardResponse::campaignId).toList();
    }

    @Test
    void 최근_60초_6회를_넘으면_남은_초를_올림해_429로_거절한다() {
        when(rateLimitRepository.acquire(eq(MEMBER_ID), anyString(), any()))
                .thenReturn(new Decision(false, NOW.plusMillis(36_200)));

        assertThatThrownBy(() -> service.getFeed(MEMBER_ID, HERE))
                .isInstanceOfSatisfying(FeedRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(37));
        verify(memberQueryService, never()).findById(any());
    }

    @Test
    void 요청_제한_카운터가_실패하면_통과시키고_지표로_센다() {
        when(rateLimitRepository.acquire(eq(MEMBER_ID), anyString(), any()))
                .thenThrow(new QueryTimeoutException("valkey"));
        candidates(campaign(1).build());

        FeedResponse response = service.getFeed(MEMBER_ID, HERE);

        assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
        assertThat(response.nextFeedAvailableAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T10:15:30+09:00"));
        assertThat(meterRegistry.counter(FeedService.RATE_LIMIT_BYPASS_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    void 바로_요청할_수_있으면_다음_요청_시각은_응답_시각이다() {
        candidates(campaign(1).build());

        FeedResponse response = service.getFeed(MEMBER_ID, HERE);

        assertThat(response.nextFeedAvailableAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T10:15:30+09:00"));
    }

    @Test
    void 이번이_여섯째_요청이면_다음_요청_시각은_가장_오래된_요청에서_60초_뒤를_올린_초다() {
        when(rateLimitRepository.acquire(eq(MEMBER_ID), anyString(), any()))
                .thenReturn(new Decision(true, NOW.plusMillis(20_500)));
        candidates(campaign(1).build());

        FeedResponse response = service.getFeed(MEMBER_ID, HERE);

        assertThat(response.nextFeedAvailableAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T10:15:51+09:00"));
    }

    @Test
    void 온보딩을_마치지_않으면_ADSERVING_001이고_요청_횟수에서_뺀다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(false, null, null)));

        assertThatThrownBy(() -> service.getFeed(MEMBER_ID, HERE))
                .isInstanceOfSatisfying(FeedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED));
        verify(rateLimitRepository).release(eq(MEMBER_ID), anyString());
    }

    @Test
    void 인증된_회원을_찾지_못하면_서버_오류로_올리고_요청_횟수에서_뺀다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFeed(MEMBER_ID, HERE)).isInstanceOf(IllegalStateException.class);
        verify(rateLimitRepository).release(eq(MEMBER_ID), anyString());
    }

    @Test
    void 위치를_보내지_않았고_저장_위치도_없으면_ADSERVING_002다() {
        assertThatThrownBy(() -> service.getFeed(MEMBER_ID, NO_LOCATION))
                .isInstanceOfSatisfying(FeedException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FeedErrorCode.LOCATION_REQUIRED));
    }

    @Test
    void 위치를_보내지_않으면_저장_위치를_기준으로_찾는다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(
                member(true, new BigDecimal("37.5079"), new BigDecimal("127.0276"))));
        candidates(campaign(1).build());

        FeedResponse response = service.getFeed(MEMBER_ID, NO_LOCATION);

        assertThat(response.cards()).singleElement()
                .satisfies(card -> assertThat(card.distanceM()).isBetween(1100, 1120));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01T00:59:59.999Z", "2026-10-01T04:00:00Z"})
    void 서빙_시간대_밖이면_카드를_찾지_않고_OUT_OF_SERVING_HOURS다(String now) {
        FeedResponse response = serviceAt(Instant.parse(now)).getFeed(MEMBER_ID, HERE);

        assertThat(response.status()).isEqualTo(FeedStatus.OUT_OF_SERVING_HOURS);
        assertThat(response.feedId()).isNull();
        assertThat(response.radiusM()).isNull();
        assertThat(response.cards()).isEmpty();
        verify(adCandidateRepository, never()).findServableIn(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01T01:00:00Z", "2026-10-01T03:59:59.999Z"})
    void 서빙_시간대는_10시_정각부터_12시_59분_59초까지다(String now) {
        candidates(campaign(1).build());

        FeedResponse response = serviceAt(Instant.parse(now)).getFeed(MEMBER_ID, HERE);

        assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
    }

    @Test
    void 성별_연령대_노출_반경이_맞지_않는_캠페인은_뺀다() {
        candidates(
                campaign(1).build(),
                campaign(2).set("targetGender", TargetGender.MALE).build(),
                campaign(3).set("targetAgeGroups", "AGE_20S,AGE_40S").build(),
                campaign(4).set("targetRadius", (short) 500).northBy(600).build(),
                campaign(5).set("targetGender", TargetGender.FEMALE).set("targetAgeGroups", "AGE_30S").build());

        assertThat(campaignIds(service.getFeed(MEMBER_ID, HERE))).containsExactlyInAnyOrder(1L, 5L);
    }

    @Test
    void 피드_필터의_업종이_아닌_캠페인은_뺀다() {
        when(feedFilterRepository.findByMemberId(MEMBER_ID))
                .thenReturn(new FeedFilterRow(Set.of("JAPANESE"), FeedSort.DISTANCE));
        candidates(campaign(1).build(), campaign(2).set("categoryCode", "JAPANESE").build());

        assertThat(campaignIds(service.getFeed(MEMBER_ID, HERE))).containsExactly(2L);
    }

    @Test
    void 오늘_찜하거나_패스한_캠페인은_뺀다_가게_상세에서_오늘_찜한_것도_뺀다_임시() {
        when(swipeLogRepository.findCampaignIdsSwipedOn(MEMBER_ID, DATE)).thenReturn(Set.of(1L));
        when(wishlistRepository.findCampaignIdsWishedSince(MEMBER_ID, DATE.atStartOfDay())).thenReturn(Set.of(2L));
        candidates(campaign(1).build(), campaign(2).build(), campaign(3).build());

        assertThat(campaignIds(service.getFeed(MEMBER_ID, HERE))).containsExactly(3L);
    }

    @Test
    void 오늘_두_번_내려준_캠페인과_이번_세션에_내려준_캠페인은_뺀다() {
        when(serveRecordRepository.findHistory(DATE, MEMBER_ID))
                .thenReturn(new ServeHistory(Map.of(1L, 2, 2L, 1, 3L, 1), Set.of(3L)));
        candidates(campaign(1).build(), campaign(2).build(), campaign(3).build());

        assertThat(campaignIds(service.getFeed(MEMBER_ID, HERE))).containsExactly(2L);
    }

    @Test
    void 그날_예산_계획이_없거나_진행률이_1_2를_넘는_캠페인은_뺀다() {
        when(budgetQueryService.getProgress(any(), eq(DATE), eq(NOW_LOCAL))).thenReturn(List.of(
                new BudgetProgress(1L, 10_000, 10, 6_000, 5_000, 1.2),
                new BudgetProgress(2L, 10_000, 10, 6_010, 5_000, 1.202)));
        candidates(campaign(1).build(), campaign(2).build(), campaign(3).build());

        assertThat(campaignIds(service.getFeed(MEMBER_ID, HERE))).containsExactly(1L);
    }

    @Test
    void 그날_유효_노출이_예약액_나누기_단가의_1_05배에_닿은_캠페인은_뺀다() {
        when(impressionCountRepository.countValidByCampaign(any(), eq(DATE))).thenReturn(Map.of(1L, 1_049L, 2L, 1_050L));
        candidates(campaign(1).build(), campaign(2).build());

        assertThat(campaignIds(service.getFeed(MEMBER_ID, HERE))).containsExactly(1L);
    }

    @Test
    void 남은_후보가_없으면_NO_CANDIDATE이고_접속_위치는_남긴다() {
        candidates(campaign(1).set("targetGender", TargetGender.MALE).build());

        FeedResponse response = service.getFeed(MEMBER_ID, HERE);

        assertThat(response.status()).isEqualTo(FeedStatus.NO_CANDIDATE);
        assertThat(response.feedId()).isNull();
        assertThat(response.radiusM()).isEqualTo(5000);
        assertThat(response.cards()).isEmpty();
        verify(serveRecordRepository, never()).save(any(), any(), anyList(), any());
        verify(feedLogWriter).write(eq(List.of()), eq(DATE), eq(MEMBER_ID), anyDouble(), anyDouble(), eq(NOW_LOCAL));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 카드마다_serve_기록을_쓰고_서빙_로그를_넘긴다() {
        candidates(campaign(101).northBy(420).build());

        FeedResponse response = service.getFeed(MEMBER_ID, HERE);

        FeedCardResponse card = response.cards().getFirst();
        assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
        assertThat(response.radiusM()).isEqualTo(5000);
        assertThat(card.servedAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T10:15:30.123+09:00"));
        assertThat(card.distanceM()).isEqualTo(420);
        assertThat(card.issueOpenTime()).isEqualTo("11:00");
        assertThat(card.usableStartTime()).isEqualTo("12:00");
        assertThat(card.posterUrl()).isEqualTo("/v1/campaigns/101/poster");

        ArgumentCaptor<List<ServeRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(serveRecordRepository).save(eq(DATE), eq(MEMBER_ID), records.capture(),
                eq(Duration.between(NOW_LOCAL, DATE.plusDays(1).atStartOfDay())));
        assertThat(records.getValue()).singleElement().satisfies(record -> {
            assertThat(record.serveId()).isEqualTo(card.serveId());
            assertThat(record.slotType()).isEqualTo(SlotType.ALLOCATION);
            assertThat(record.slotPosition()).isEqualTo(1);
        });

        ArgumentCaptor<List<ServeLogRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(feedLogWriter).write(rows.capture(), eq(DATE), eq(MEMBER_ID), eq(37.4979), eq(127.0276), eq(NOW_LOCAL));
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.serveId()).isEqualTo(card.serveId());
            assertThat(row.feedId()).isEqualTo(response.feedId());
        });
    }

    @Test
    void serve_기록_쓰기가_실패하면_피드를_실패시키고_요청_횟수에서_뺀다_임시() {
        candidates(campaign(1).build());
        doThrow(new QueryTimeoutException("valkey"))
                .when(serveRecordRepository).save(any(), any(), anyList(), any());

        assertThatThrownBy(() -> service.getFeed(MEMBER_ID, HERE)).isInstanceOf(QueryTimeoutException.class);
        verify(rateLimitRepository).release(eq(MEMBER_ID), anyString());
        verify(feedLogWriter, never()).write(anyList(), any(), any(), anyDouble(), anyDouble(), any());
    }

    @Test
    void 반경이_넓어져도_후보를_예산_조회에_한_번에_넘긴다() {
        List<AdCandidate> many = new ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            many.add(campaign(id).northBy(150 + 100 * id).build());
        }
        when(adCandidateRepository.findServableIn(eq(DATE), any(), any(), any(), any())).thenReturn(many);

        FeedResponse response = service.getFeed(MEMBER_ID, HERE);

        assertThat(response.radiusM()).isEqualTo(2000);
        assertThat(response.cards()).hasSize(10);
        verify(budgetQueryService).getProgress(any(), eq(DATE), eq(NOW_LOCAL));
    }

    @Test
    void 최근_30일_찜_비율을_업종_선호도로_쓴다_임시() {
        when(swipeLogRepository.countWishesByCategory(MEMBER_ID, LocalDate.of(2026, 9, 2), DATE))
                .thenReturn(Map.of("JAPANESE", 3L));
        List<AdCandidate> many = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            many.add(campaign(id).northBy(100).build());
        }
        many.add(campaign(11).set("categoryCode", "JAPANESE").northBy(500).build());
        when(adCandidateRepository.findServableIn(eq(DATE), any(), any(), any(), any())).thenReturn(many);

        assertThat(campaignIds(service.getFeed(MEMBER_ID, HERE))).contains(11L);
    }
}
