package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.FeedFilter;
import com.launchcatch.adserving.engine.ServableCandidate;
import com.launchcatch.adserving.engine.SlotType;
import com.launchcatch.adserving.engine.repository.AdCandidateQueryRepository;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.dto.FeedStatus;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.FeedFilterQueryRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository;
import com.launchcatch.adserving.feed.repository.RateLimitResult;
import com.launchcatch.adserving.feed.repository.ServeHistory;
import com.launchcatch.adserving.feed.repository.ServeHistoryRepository;
import com.launchcatch.adserving.feed.repository.SwipeLogQueryRepository;
import com.launchcatch.adserving.impression.repository.ImpressionLogQueryRepository;
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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class FeedServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 15, 30, 123_000_000);
    private static final OffsetDateTime NOW_OFFSET = NOW.atOffset(ZoneOffset.ofHours(9));
    private static final double REQUEST_LAT = 37.4979;
    private static final double REQUEST_LNG = 127.0276;
    private static final BigDecimal SAVED_LAT = new BigDecimal("37.5665000");
    private static final BigDecimal SAVED_LNG = new BigDecimal("126.9780000");
    private static final double METERS_PER_LAT_DEGREE = 111_195.08;

    @Mock MemberQueryService memberQueryService;
    @Mock BudgetQueryService budgetQueryService;
    @Mock FeedRateLimitRepository rateLimitRepository;
    @Mock ServeHistoryRepository serveHistoryRepository;
    @Mock AdCandidateQueryRepository adCandidateRepository;
    @Mock FeedFilterQueryRepository feedFilterRepository;
    @Mock SwipeLogQueryRepository swipeLogRepository;
    @Mock ImpressionLogQueryRepository impressionLogRepository;
    @Mock ServeLogWriter serveLogWriter;

    private SimpleMeterRegistry meterRegistry;
    private FeedService feedService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        feedService = serviceAt(NOW);

        lenient().when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true, SAVED_LAT, SAVED_LNG)));
        lenient().when(rateLimitRepository.tryAcquire(eq(MEMBER_ID), any(), anyInt(), any()))
                .thenReturn(new RateLimitResult(true, 1, NOW_OFFSET.toInstant()));
        lenient().when(serveHistoryRepository.find(MEMBER_ID, TODAY)).thenReturn(ServeHistory.EMPTY);
        lenient().when(feedFilterRepository.find(MEMBER_ID)).thenReturn(FeedFilter.DEFAULT);
        lenient().when(swipeLogRepository.findSwipedCampaignIds(MEMBER_ID, TODAY)).thenReturn(Set.of());
        lenient().when(swipeLogRepository.countWishesByCategory(eq(MEMBER_ID), any(), any())).thenReturn(Map.of());
        lenient().when(impressionLogRepository.countValidByCampaign(eq(TODAY), anyCollection())).thenReturn(Map.of());
        lenient().when(adCandidateRepository.findServable(eq(TODAY), any()))
                .thenReturn(List.of(candidate(101L, REQUEST_LAT, REQUEST_LNG, 420)));
        lenient().when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(NOW)))
                .thenAnswer(invocation -> progressFor(invocation.getArgument(0)));
    }

    @Test
    void 온보딩을_마치지_않았으면_ADSERVING_001이다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(false, SAVED_LAT, SAVED_LNG)));

        assertThatThrownBy(() -> feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG))
                .isInstanceOf(FeedException.class)
                .extracting("errorCode").isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED);
        verify(rateLimitRepository, never()).tryAcquire(anyLong(), any(), anyInt(), any());
    }

    @Test
    void 회원_정보가_없으면_ADSERVING_001이다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG))
                .extracting("errorCode").isEqualTo(FeedErrorCode.ONBOARDING_REQUIRED);
    }

    @Test
    void 위치를_보내지_않았고_저장_위치도_없으면_ADSERVING_002다() {
        when(memberQueryService.findById(MEMBER_ID)).thenReturn(Optional.of(member(true, null, null)));

        assertThatThrownBy(() -> feedService.getFeed(MEMBER_ID, null, null))
                .extracting("errorCode").isEqualTo(FeedErrorCode.LOCATION_REQUIRED);
        verify(rateLimitRepository, never()).tryAcquire(anyLong(), any(), anyInt(), any());
    }

    @Test
    void 위치를_보내지_않으면_저장_위치에서_거리를_잰다() {
        when(adCandidateRepository.findServable(eq(TODAY), any()))
                .thenReturn(List.of(candidate(101L, SAVED_LAT.doubleValue(), SAVED_LNG.doubleValue(), 300)));

        FeedResponse response = feedService.getFeed(MEMBER_ID, null, null);

        assertThat(response.cards()).extracting(FeedCardResponse::distanceM).containsExactly(300);
    }

    @Test
    void 위치를_보내면_저장_위치보다_요청_위치를_쓴다() {
        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.cards()).extracting(FeedCardResponse::distanceM).containsExactly(420);
    }

    @Test
    void 최근_60초에_6회를_넘으면_429와_다시_요청할_수_있을_때까지_남은_초를_올림해_준다() {
        Instant oldest = NOW_OFFSET.toInstant().minusMillis(23_500);
        when(rateLimitRepository.tryAcquire(MEMBER_ID, NOW_OFFSET.toInstant(), 6, Duration.ofSeconds(60)))
                .thenReturn(new RateLimitResult(false, 6, oldest));

        assertThatThrownBy(() -> feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG))
                .isInstanceOfSatisfying(FeedRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(37));
        verify(adCandidateRepository, never()).findServable(any(), any());
    }

    @Test
    void 여섯째_요청이면_다음_피드_시각은_가장_오래된_요청의_60초_뒤다() {
        when(rateLimitRepository.tryAcquire(eq(MEMBER_ID), any(), anyInt(), any()))
                .thenReturn(new RateLimitResult(true, 6, NOW_OFFSET.toInstant().minusSeconds(20)));

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.nextFeedAvailableAt()).isEqualTo(NOW_OFFSET.plusSeconds(40));
    }

    @Test
    void 다섯째_요청까지는_다음_피드_시각이_응답_시각이다() {
        when(rateLimitRepository.tryAcquire(eq(MEMBER_ID), any(), anyInt(), any()))
                .thenReturn(new RateLimitResult(true, 5, NOW_OFFSET.toInstant().minusSeconds(20)));

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.nextFeedAvailableAt()).isEqualTo(NOW_OFFSET);
    }

    @Test
    void 요청_제한_저장소가_멈추면_통과시키고_센다() {
        when(rateLimitRepository.tryAcquire(eq(MEMBER_ID), any(), anyInt(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
        assertThat(response.nextFeedAvailableAt()).isEqualTo(NOW_OFFSET);
        assertThat(bypassCount("rate_limit")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @CsvSource({"09:59:59.999, OUT_OF_SERVING_HOURS", "10:00:00, SERVING", "12:59:59.999, SERVING",
            "13:00:00, OUT_OF_SERVING_HOURS"})
    void 서빙_시간대는_10시부터_12시_59분까지다(LocalTime time, FeedStatus expected) {
        LocalDateTime at = TODAY.atTime(time);
        lenient().when(budgetQueryService.getProgress(anyCollection(), eq(TODAY), eq(at)))
                .thenAnswer(invocation -> progressFor(invocation.getArgument(0)));

        FeedResponse response = serviceAt(at).getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.status()).isEqualTo(expected);
    }

    @Test
    void 서빙_시간대_밖이면_후보를_찾지_않지만_요청_횟수에는_넣는다() {
        FeedResponse response = serviceAt(TODAY.atTime(13, 0)).getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.feedId()).isNull();
        assertThat(response.radiusM()).isNull();
        assertThat(response.cards()).isEmpty();
        verify(rateLimitRepository).tryAcquire(eq(MEMBER_ID), any(), anyInt(), any());
        verify(adCandidateRepository, never()).findServable(any(), any());
    }

    @Test
    void 후보가_없으면_NO_CANDIDATE와_반경_5000이다() {
        when(adCandidateRepository.findServable(eq(TODAY), any())).thenReturn(List.of());

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.status()).isEqualTo(FeedStatus.NO_CANDIDATE);
        assertThat(response.feedId()).isNull();
        assertThat(response.radiusM()).isEqualTo(5000);
        assertThat(response.cards()).isEmpty();
        verify(budgetQueryService, never()).getProgress(anyCollection(), any(), any());
        verify(serveHistoryRepository, never()).record(anyLong(), any(), anyList());
    }

    @Test
    void 카드를_명세의_필드대로_채운다() {
        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
        assertThat(response.feedId().version()).isEqualTo(7);
        // 후보가 한 장이라 10장을 채우려고 끝까지 넓힌다
        assertThat(response.radiusM()).isEqualTo(5000);
        FeedCardResponse card = response.cards().getFirst();
        assertThat(card.serveId().version()).isEqualTo(7);
        assertThat(card.servedAt()).isEqualTo(NOW_OFFSET);
        assertThat(card.campaignId()).isEqualTo(101L);
        assertThat(card.storeId()).isEqualTo(55L);
        assertThat(card.storeName()).isEqualTo("점심식당");
        assertThat(card.categoryCode()).isEqualTo("KOREAN");
        assertThat(card.distanceM()).isEqualTo(420);
        assertThat(card.discount()).isEqualTo(new FeedCardResponse.Discount("ALL", "PERCENT", 20));
        assertThat(card.issueOpenTime()).isEqualTo("11:00");
        assertThat(card.usableStartTime()).isEqualTo("12:00");
        assertThat(card.usableEndTime()).isEqualTo("14:00");
        assertThat(card.issueQuantity()).isEqualTo(30);
        assertThat(card.posterUrl()).isEqualTo("/v1/campaigns/101/poster");
    }

    @Test
    void 내려준_카드를_serve_기록으로_남기고_서빙_로그와_반올림한_위치를_비동기로_넘긴다() {
        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ServeRecord>> records = ArgumentCaptor.forClass(List.class);
        verify(serveHistoryRepository).record(eq(MEMBER_ID), eq(TODAY), records.capture());
        ServeRecord record = records.getValue().getFirst();
        assertThat(record.serveId()).isEqualTo(response.cards().getFirst().serveId());
        assertThat(record.feedId()).isEqualTo(response.feedId());
        assertThat(record.slotType()).isEqualTo(SlotType.ALLOCATION);
        assertThat(record.slotPosition()).isEqualTo(1);
        assertThat(record.storeId()).isEqualTo(55L);
        assertThat(record.servedAt()).isEqualTo(NOW);
        verify(serveLogWriter).write(MEMBER_ID, TODAY, records.getValue(),
                new BigDecimal("37.498"), new BigDecimal("127.028"));
    }

    @Test
    void 카드가_없어도_그날_접속_위치는_넘긴다() {
        FeedResponse response = serviceAt(TODAY.atTime(9, 0)).getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.status()).isEqualTo(FeedStatus.OUT_OF_SERVING_HOURS);
        verify(serveLogWriter).write(MEMBER_ID, TODAY, List.of(), new BigDecimal("37.498"), new BigDecimal("127.028"));
    }

    @Test
    void 오늘_2회_받은_캠페인과_이번_세션에_받은_캠페인은_뺀다() {
        when(adCandidateRepository.findServable(eq(TODAY), any())).thenReturn(List.of(
                candidate(101L, REQUEST_LAT, REQUEST_LNG, 100),
                candidate(102L, REQUEST_LAT, REQUEST_LNG, 100),
                candidate(103L, REQUEST_LAT, REQUEST_LNG, 100)));
        when(serveHistoryRepository.find(MEMBER_ID, TODAY))
                .thenReturn(new ServeHistory(Map.of(101L, 2, 102L, 1), Set.of(103L)));

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(102L);
    }

    @Test
    void 오늘_찜하거나_패스한_캠페인은_뺀다() {
        when(adCandidateRepository.findServable(eq(TODAY), any())).thenReturn(List.of(
                candidate(101L, REQUEST_LAT, REQUEST_LNG, 100),
                candidate(102L, REQUEST_LAT, REQUEST_LNG, 100)));
        when(swipeLogRepository.findSwipedCampaignIds(MEMBER_ID, TODAY)).thenReturn(Set.of(101L));

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.cards()).extracting(FeedCardResponse::campaignId).containsExactly(102L);
    }

    @Test
    void 그날_유효_노출이_상한에_닿은_캠페인은_뺀다() {
        // 예약액 100,000원, 단가 10원이면 상한은 10,500회다
        when(impressionLogRepository.countValidByCampaign(eq(TODAY), anyCollection())).thenReturn(Map.of(101L, 10_500L));

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.status()).isEqualTo(FeedStatus.NO_CANDIDATE);
    }

    @Test
    void 카테고리_선호도는_오늘까지_30일의_찜으로_잰다() {
        feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        verify(swipeLogRepository).countWishesByCategory(MEMBER_ID, TODAY.minusDays(29), TODAY);
    }

    @Test
    void 이력을_읽지_못하면_통과시키고_센다() {
        when(serveHistoryRepository.find(MEMBER_ID, TODAY)).thenThrow(new RedisConnectionFailureException("down"));

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
        assertThat(bypassCount("history_read")).isEqualTo(1.0);
    }

    @Test
    void serve_기록을_쓰지_못해도_피드는_내려주고_센다() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(serveHistoryRepository).record(eq(MEMBER_ID), eq(TODAY), anyList());

        FeedResponse response = feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        assertThat(response.status()).isEqualTo(FeedStatus.SERVING);
        assertThat(bypassCount("history_write")).isEqualTo(1.0);
    }

    @Test
    void 예산_진행률은_후보를_5000개씩_나눠_조회한다() {
        List<ServableCandidate> many = LongStream.rangeClosed(1, 5001)
                .mapToObj(id -> candidate(id, REQUEST_LAT, REQUEST_LNG, 100))
                .toList();
        when(adCandidateRepository.findServable(eq(TODAY), any())).thenReturn(many);

        feedService.getFeed(MEMBER_ID, REQUEST_LAT, REQUEST_LNG);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(budgetQueryService, times(2)).getProgress(ids.capture(), eq(TODAY), eq(NOW));
        assertThat(ids.getAllValues()).extracting(Collection::size).containsExactly(5000, 1);
    }

    private FeedService serviceAt(LocalDateTime at) {
        Clock clock = Clock.fixed(at.atZone(ClockConfig.ZONE).toInstant(), ClockConfig.ZONE);
        return new FeedService(memberQueryService, budgetQueryService, rateLimitRepository, serveHistoryRepository,
                adCandidateRepository, feedFilterRepository, swipeLogRepository, impressionLogRepository,
                serveLogWriter, meterRegistry, clock);
    }

    private double bypassCount(String operation) {
        return meterRegistry.counter(FeedService.CACHE_BYPASS_METRIC, "operation", operation).count();
    }

    private static List<BudgetProgress> progressFor(Collection<Long> campaignIds) {
        return campaignIds.stream()
                .map(id -> new BudgetProgress(id, 100_000, 10, 0, 0, 0.5))
                .toList();
    }

    private static MemberInfo member(boolean onboarded, BigDecimal latitude, BigDecimal longitude) {
        return new MemberInfo(MEMBER_ID, "닉네임", MemberStatus.ACTIVE, onboarded, true, true, latitude, longitude,
                Gender.MALE, AgeGroup.AGE_30S);
    }

    // 기준 위치에서 북쪽으로 distanceM 떨어진 후보다
    private static ServableCandidate candidate(long campaignId, double baseLat, double baseLng, int distanceM) {
        return new ServableCandidate(campaignId, 55L, "점심식당", "KOREAN", baseLat + distanceM / METERS_PER_LAT_DEGREE,
                baseLng, 3000, "ALL", Set.of("AGE_30S"), "ALL", "PERCENT", 20, LocalTime.of(12, 0),
                LocalTime.of(14, 0), 30, LocalDateTime.of(2026, 9, 1, 9, 0));
    }
}
