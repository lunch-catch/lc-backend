package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.Assembly;
import com.launchcatch.adserving.engine.BudgetRule;
import com.launchcatch.adserving.engine.Candidate;
import com.launchcatch.adserving.engine.Eligible;
import com.launchcatch.adserving.engine.FeedAssembler;
import com.launchcatch.adserving.engine.FeedFilter;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.ServedFeed;
import com.launchcatch.adserving.feed.ServedFeed.ServedCard;
import com.launchcatch.adserving.feed.UuidV7;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
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
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.geo.BoundingBox;
import com.launchcatch.global.geo.GeoDistance;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * 스와이프 피드 한 번을 내려준다(67, 96, 97, 99행).
 *
 * 요청 제한의 자리를 먼저 잡고, 200 으로 끝나지 않으면 그 자리를 돌려놓는다.
 * 후보는 그날 스냅샷(ad_candidate)에서만 찾고, 다른 도메인에서는 회원 정보와 예산 진행률만 읽는다.
 * 트랜잭션을 걸지 않는다. 읽기만 하고, 쓰기는 Valkey 와 응답 뒤의 비동기 저장뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    static final String FALLBACK_METRIC = "adserving.feed.fallback";

    private static final LocalTime SERVING_START = LocalTime.of(10, 0);
    private static final LocalTime SERVING_END = LocalTime.of(13, 0);

    // 임시(#230): ops 가 하루 상한을 공개하지 않아 상수로 둔다
    private static final long DAILY_SERVE_CAP = 2;
    private static final int PREFERENCE_DAYS = 30;

    private final MemberQueryService memberQueryService;
    /*
     * 정산이 구현을 아직 내놓지 않았다(#193).
     * 구현이 없으면 예산을 읽지 못한 회차와 같이 처리하고, 구현이 들어오면 코드를 바꾸지 않아도 예산 조건이 걸린다.
     */
    private final ObjectProvider<BudgetQueryService> budgetQueryService;
    private final AdCandidateRepository adCandidateRepository;
    private final ImpressionLogRepository impressionLogRepository;
    private final SwipeLogRepository swipeLogRepository;
    private final FeedFilterRepository feedFilterRepository;
    private final FeedRateLimitRepository feedRateLimitRepository;
    private final ServeCacheRepository serveCacheRepository;
    private final ServeRecordService serveRecordService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public FeedResponse getFeed(Long memberId, FeedRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        String requestId = UUID.randomUUID().toString();
        OffsetDateTime nextFeedAvailableAt = acquire(memberId, requestId, now);
        try {
            return serve(memberId, request, now, nextFeedAvailableAt);
        } catch (RuntimeException e) {
            release(memberId, requestId);
            throw e;
        }
    }

    private FeedResponse serve(Long memberId, FeedRequest request, LocalDateTime now, OffsetDateTime nextFeedAvailableAt) {
        MemberInfo member = memberQueryService.findById(memberId)
                .filter(FeedService::onboarded)
                .orElseThrow(() -> new FeedException(FeedErrorCode.ONBOARDING_REQUIRED));
        Location location = locate(member, request);
        if (!servingHours(now.toLocalTime())) {
            return FeedResponse.outOfServingHours(nextFeedAvailableAt);
        }

        LocalDate today = now.toLocalDate();
        FeedFilter filter = feedFilterRepository.find(memberId);
        List<Eligible> eligible = findEligible(member, location, today, now, filter);
        Map<String, Long> wishCounts =
                swipeLogRepository.countWishesByCategory(memberId, today.minusDays(PREFERENCE_DAYS - 1L), today);
        Assembly assembly = FeedAssembler.assemble(eligible, today, wishCounts, filter.sort());

        long nowMillis = toMillis(now);
        List<ServedCard> cards = assembly.cards().stream()
                .map(card -> new ServedCard(UuidV7.at(nowMillis), card))
                .toList();
        ServedFeed feed = new ServedFeed(UuidV7.at(nowMillis), memberId, today, now, location.latitude(), location.longitude(), cards);
        record(feed);

        if (cards.isEmpty()) {
            return FeedResponse.noCandidate(assembly.radiusM(), nextFeedAvailableAt);
        }
        List<FeedCardResponse> responses = cards.stream().map(card -> FeedCardResponse.of(card, now)).toList();
        return FeedResponse.serving(feed.feedId(), assembly.radiusM(), nextFeedAvailableAt, responses);
    }

    /*
     * 거리 말고 모든 후보 조건을 건다.
     * 거리는 반경을 넓혀 가며 FeedAssembler 가 건다.
     */
    private List<Eligible> findEligible(
            MemberInfo member, Location location, LocalDate today, LocalDateTime now, FeedFilter filter) {
        BoundingBox box = BoundingBox.around(location.latitude(), location.longitude(), FeedAssembler.MAX_SEARCH_RADIUS);
        Set<Long> swiped = swipeLogRepository.findCampaignIdsSwipedOn(member.memberId(), today);
        ServeHistory history = findHistory(member.memberId(), today);
        String gender = member.gender().name();
        String ageGroup = member.ageGroup().name();

        List<Candidate> targeted = adCandidateRepository.findServable(today, box).stream()
                .filter(c -> c.targets(gender, ageGroup))
                .filter(c -> filter.allows(c.categoryCode()))
                .filter(c -> !swiped.contains(c.campaignId()))
                .filter(c -> history.allows(c.campaignId(), DAILY_SERVE_CAP))
                .toList();
        Map<Long, Double> progressRates = progressRatesWithinBudget(targeted, today, now);

        return targeted.stream()
                .filter(c -> progressRates.containsKey(c.campaignId()))
                .map(c -> new Eligible(c, distance(location, c), progressRates.get(c.campaignId())))
                .toList();
    }

    /*
     * 예산 조건을 통과한 캠페인의 진행률을 돌려준다. 그날 예산 계획이 없는 캠페인은 빠진다.
     *
     * 임시(#230): 정산이 진행률을 주지 못하면 예산 조건 없이 모두 통과시키고 진행률을 0 으로 둔다.
     * 과금은 정산이 차감할 때 따로 판정하므로(NOT_BILLED_BUDGET) 점주 돈은 틀어지지 않는다.
     * 막으면 정산 카운터 장애가 피드 전면 장애가 된다. 통과시킨 횟수를 센다.
     */
    private Map<Long, Double> progressRatesWithinBudget(List<Candidate> targeted, LocalDate today, LocalDateTime now) {
        List<Long> campaignIds = targeted.stream().map(Candidate::campaignId).toList();
        if (campaignIds.isEmpty()) {
            return Map.of();
        }
        List<BudgetProgress> progresses;
        try {
            progresses = fetchProgress(campaignIds, today, now);
        } catch (RuntimeException e) {
            countFallback("budget", e);
            return campaignIds.stream().collect(Collectors.toMap(id -> id, id -> 0.0));
        }
        Map<Long, Long> validImpressions = impressionLogRepository.countValidByCampaign(today, campaignIds);
        return progresses.stream()
                .filter(p -> !BudgetRule.excludes(p, validImpressions.getOrDefault(p.campaignId(), 0L)))
                .collect(Collectors.toMap(BudgetProgress::campaignId, BudgetProgress::progressRate));
    }

    // 정산이 한 번에 받는 캠페인 수를 넘으면 나눠 부른다
    private List<BudgetProgress> fetchProgress(List<Long> campaignIds, LocalDate today, LocalDateTime now) {
        BudgetQueryService budget = budgetQueryService.getIfAvailable();
        if (budget == null) {
            throw new IllegalStateException("BudgetQueryService 구현이 없다");
        }
        List<BudgetProgress> progresses = new ArrayList<>();
        for (int from = 0; from < campaignIds.size(); from += BudgetQueryService.MAX_CAMPAIGN_IDS) {
            int to = Math.min(from + BudgetQueryService.MAX_CAMPAIGN_IDS, campaignIds.size());
            progresses.addAll(budget.getProgress(campaignIds.subList(from, to), today, now));
        }
        return progresses;
    }

    /*
     * 요청 제한의 자리를 잡고 다음 피드를 받을 수 있는 시각을 돌려준다.
     * Valkey 가 멈추면 막지 않고 통과시킨다. 캐시 장애가 피드 전면 장애가 되지 않게 하는 기본 처리다.
     */
    private OffsetDateTime acquire(Long memberId, String requestId, LocalDateTime now) {
        long nowMillis = toMillis(now);
        Acquisition acquisition;
        try {
            acquisition = feedRateLimitRepository.acquire(memberId, requestId, nowMillis);
        } catch (DataAccessException e) {
            countFallback("rate_limit", e);
            return toOffset(nowMillis);
        }
        if (!acquisition.acquired()) {
            throw new FeedRateLimitedException(ceilSeconds(acquisition.availableAtMillis() - nowMillis));
        }
        return toOffset(acquisition.availableAtMillis());
    }

    /*
     * 돌려놓기가 실패하면 그 자리는 창이 지나며 사라진다.
     * 원래 오류를 가리지 않게 삼킨다.
     */
    private void release(Long memberId, String requestId) {
        try {
            feedRateLimitRepository.release(memberId, requestId);
        } catch (DataAccessException e) {
            log.warn("feed rate limit release failed. cause={}", e.getClass().getSimpleName());
        }
    }

    /*
     * 당일 이력을 읽지 못하면 이력 조건 없이 통과시킨다.
     * 같은 캠페인이 한 번 더 나가는 것이 피드를 막는 것보다 낫다.
     */
    private ServeHistory findHistory(Long memberId, LocalDate today) {
        try {
            return serveCacheRepository.findHistory(memberId, today);
        } catch (DataAccessException e) {
            countFallback("serve_history", e);
            return ServeHistory.EMPTY;
        }
    }

    /*
     * serve 기록을 Valkey 에 쓰고 서빙 로그와 접속 위치를 비동기 저장에 넘긴다.
     * serve 기록을 쓰지 못해도 피드는 내려준다. 그 카드의 노출은 EXPIRED_OR_UNKNOWN 으로 판정되어 과금되지 않는다.
     */
    private void record(ServedFeed feed) {
        try {
            serveCacheRepository.record(feed);
        } catch (DataAccessException e) {
            countFallback("serve_cache", e);
        }
        serveRecordService.recordAsync(feed);
    }

    private void countFallback(String target, RuntimeException e) {
        meterRegistry.counter(FALLBACK_METRIC, "target", target).increment();
        log.debug("feed fallback. target={}, cause={}", target, e.getClass().getSimpleName());
    }

    // 임시(#230): 성별과 연령대가 없으면 대상 필터를 걸 수 없어 온보딩 전으로 본다
    private static boolean onboarded(MemberInfo member) {
        return member.onboardingCompleted() && member.gender() != null && member.ageGroup() != null;
    }

    // 요청 위치가 없으면 저장 위치를 쓴다(67행)
    private static Location locate(MemberInfo member, FeedRequest request) {
        if (request.hasCoordinate()) {
            return new Location(request.latitude(), request.longitude());
        }
        if (member.latitude() == null || member.longitude() == null) {
            throw new FeedException(FeedErrorCode.LOCATION_REQUIRED);
        }
        return new Location(member.latitude().doubleValue(), member.longitude().doubleValue());
    }

    private static boolean servingHours(LocalTime time) {
        return !time.isBefore(SERVING_START) && time.isBefore(SERVING_END);
    }

    private static int distance(Location location, Candidate candidate) {
        return (int) Math.round(GeoDistance.meters(
                location.latitude(), location.longitude(), candidate.latitude(), candidate.longitude()));
    }

    private static long ceilSeconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    private static long toMillis(LocalDateTime time) {
        return time.atZone(ClockConfig.ZONE).toInstant().toEpochMilli();
    }

    private static OffsetDateTime toOffset(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ClockConfig.ZONE).toOffsetDateTime();
    }

    private record Location(double latitude, double longitude) {
    }
}
