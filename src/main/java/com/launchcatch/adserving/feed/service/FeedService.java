package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.FeedAssembler;
import com.launchcatch.adserving.engine.FeedAssembler.Feed;
import com.launchcatch.adserving.engine.FeedAssembler.Option;
import com.launchcatch.adserving.engine.FeedAssembler.Slot;
import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.entity.TargetGender;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.UuidV7;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedLocationRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository.FeedFilterRow;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository;
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
import com.launchcatch.global.geo.BoundingBox;
import com.launchcatch.global.geo.GeoDistance;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * GET /v1/feed 다. 그날 후보 스냅샷에서 사용자에게 보일 수 있는 후보를 거르고 카드 10장의 자리를 정한다.
 *
 * 순서는 요청 제한, 사용자 확인, 서빙 시간대, 후보 거르기, 자리 정하기, serve 기록 저장, 서빙 로그 넘기기다.
 * 200 으로 끝나지 않은 요청은 요청 제한의 횟수에서 뺀다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    static final String RATE_LIMIT_BYPASS_METRIC = "adserving_feed_rate_limit_bypassed_total";

    static final LocalTime SERVING_START = LocalTime.of(10, 0);
    static final LocalTime SERVING_END = LocalTime.of(13, 0);

    // 같은 사용자에게 같은 캠페인을 하루에 내려주는 최대 횟수다. ops 설정값이 없어 #236 에서 상수로 두었다
    static final int DAILY_SERVE_CAP = 2;

    // 예산 소진 진행률이 이 값을 넘으면 두 슬롯 모두에서 뺀다(99행)
    static final double MAX_PROGRESS_RATE = 1.2;

    // 노출 상한은 그날 예약액 / 단가 x 1.05 다. 정수로 비교하려고 100 을 곱해 둔다
    private static final long IMPRESSION_CAP_PERCENT = 105;

    private static final int PREFERENCE_DAYS = 30;

    private final Clock clock;
    private final FeedRateLimitRepository rateLimitRepository;
    private final ServeRecordRepository serveRecordRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final FeedFilterRepository feedFilterRepository;
    private final SwipeLogRepository swipeLogRepository;
    private final WishlistRepository wishlistRepository;
    private final ImpressionCountRepository impressionCountRepository;
    private final MemberQueryService memberQueryService;
    private final BudgetQueryService budgetQueryService;
    private final FeedLogWriter feedLogWriter;
    private final MeterRegistry meterRegistry;

    public FeedResponse getFeed(Long memberId, FeedLocationRequest location) {
        Instant now = clock.instant();
        String entry = UUID.randomUUID().toString();
        Instant nextAvailableAt = acquire(memberId, entry, now);
        try {
            return serve(memberId, location, now, toOffset(nextAvailableAt, now));
        } catch (RuntimeException e) {
            release(memberId, entry);
            throw e;
        }
    }

    /*
     * 요청 제한 카운터가 실패하면 통과시키고 지표로 센다. 캐시 기본 처리를 따른다.
     * 세지 못하는 동안 다음 요청 시각은 지금으로 답한다.
     */
    private Instant acquire(Long memberId, String entry, Instant now) {
        FeedRateLimitRepository.Decision decision;
        try {
            decision = rateLimitRepository.acquire(memberId, entry, now);
        } catch (DataAccessException e) {
            meterRegistry.counter(RATE_LIMIT_BYPASS_METRIC).increment();
            log.warn("event=FEED_RATE_LIMIT_BYPASSED memberId={} 통과시킨다", memberId, e);
            return now;
        }
        if (!decision.accepted()) {
            long millis = Duration.between(now, decision.nextAvailableAt()).toMillis();
            throw new FeedRateLimitedException(Math.max(1, (millis + 999) / 1000));
        }
        return decision.nextAvailableAt();
    }

    private void release(Long memberId, String entry) {
        try {
            rateLimitRepository.release(memberId, entry);
        } catch (DataAccessException e) {
            log.warn("event=FEED_RATE_LIMIT_RELEASE_FAILED memberId={}", memberId, e);
        }
    }

    private FeedResponse serve(Long memberId, FeedLocationRequest location, Instant now,
                               OffsetDateTime nextFeedAvailableAt) {
        MemberInfo member = memberQueryService.findById(memberId)
                .orElseThrow(() -> new IllegalStateException("인증된 회원이 없다. memberId=" + memberId));
        if (!member.onboardingCompleted() || member.gender() == null || member.ageGroup() == null) {
            throw new FeedException(FeedErrorCode.ONBOARDING_REQUIRED);
        }
        Point point = resolveLocation(location, member);

        LocalDateTime nowLocal = LocalDateTime.ofInstant(now, ClockConfig.ZONE);
        LocalTime time = nowLocal.toLocalTime();
        if (time.isBefore(SERVING_START) || !time.isBefore(SERVING_END)) {
            return FeedResponse.outOfServingHours(nextFeedAvailableAt);
        }

        LocalDate businessDate = nowLocal.toLocalDate();
        FeedFilterRow filter = feedFilterRepository.findByMemberId(memberId);
        Map<Long, AdCandidate> candidates = new HashMap<>();
        Feed feed = FeedAssembler.assemble(
                options(member, point, filter, businessDate, nowLocal, candidates), filter.sort());
        if (feed.slots().isEmpty()) {
            feedLogWriter.write(List.of(), businessDate, memberId, point.latitude(), point.longitude(), nowLocal);
            return FeedResponse.noCandidate(feed.radiusM(), nextFeedAvailableAt);
        }
        return toServing(memberId, feed, candidates, point, businessDate, nowLocal, nextFeedAvailableAt);
    }

    private record Point(double latitude, double longitude) {
    }

    // 위치를 보내지 않으면 저장 위치를 쓴다(67행)
    private static Point resolveLocation(FeedLocationRequest location, MemberInfo member) {
        if (location.isPresent()) {
            return new Point(location.latitude(), location.longitude());
        }
        if (member.latitude() == null || member.longitude() == null) {
            throw new FeedException(FeedErrorCode.LOCATION_REQUIRED);
        }
        return new Point(member.latitude().doubleValue(), member.longitude().doubleValue());
    }

    // 고른 후보를 캠페인 ID 로 candidates 에 담는다. 자리를 정한 뒤 카드를 만들 때 쓴다
    private List<Option> options(MemberInfo member, Point point, FeedFilterRow filter, LocalDate businessDate,
                                 LocalDateTime now, Map<Long, AdCandidate> candidates) {
        int maxRadius = FeedAssembler.SEARCH_RADII.getLast();
        BoundingBox box = BoundingBox.around(point.latitude(), point.longitude(), maxRadius);
        Set<Long> excluded = new HashSet<>(swipeLogRepository.findCampaignIdsSwipedOn(member.memberId(), businessDate));
        excluded.addAll(wishlistRepository.findCampaignIdsWishedSince(member.memberId(), businessDate.atStartOfDay()));
        ServeHistory history = serveRecordRepository.findHistory(businessDate, member.memberId());

        Map<Long, Double> distances = new HashMap<>();
        List<AdCandidate> targeted = new ArrayList<>();
        for (AdCandidate candidate : adCandidateRepository.findServableIn(businessDate,
                BigDecimal.valueOf(box.minLatitude()), BigDecimal.valueOf(box.maxLatitude()),
                BigDecimal.valueOf(box.minLongitude()), BigDecimal.valueOf(box.maxLongitude()))) {
            double distance = GeoDistance.meters(point.latitude(), point.longitude(),
                    candidate.getLatitude().doubleValue(), candidate.getLongitude().doubleValue());
            if (distance <= Math.min(candidate.getTargetRadius(), maxRadius)
                    && targets(candidate, member)
                    && (filter.categories().isEmpty() || filter.categories().contains(candidate.getCategoryCode()))
                    && !excluded.contains(candidate.getCampaignId())
                    && history.dailyCounts().getOrDefault(candidate.getCampaignId(), 0) < DAILY_SERVE_CAP
                    && !history.sessionCampaignIds().contains(candidate.getCampaignId())) {
                distances.put(candidate.getCampaignId(), distance);
                targeted.add(candidate);
            }
        }
        if (targeted.isEmpty()) {
            return List.of();
        }

        List<Long> campaignIds = targeted.stream().map(AdCandidate::getCampaignId).toList();
        Map<Long, BudgetProgress> progress = progress(campaignIds, businessDate, now);
        Map<Long, Long> impressions = impressionCountRepository.countValidByCampaign(progress.keySet(), businessDate);
        Map<String, Double> preferences = preferences(member.memberId(), businessDate);

        List<Option> options = new ArrayList<>();
        for (AdCandidate candidate : targeted) {
            BudgetProgress budget = progress.get(candidate.getCampaignId());
            if (budget == null || budget.progressRate() > MAX_PROGRESS_RATE
                    || reachedImpressionCap(budget, impressions.getOrDefault(candidate.getCampaignId(), 0L))) {
                continue;
            }
            candidates.put(candidate.getCampaignId(), candidate);
            options.add(new Option(candidate.getCampaignId(), candidate.getCampaignCreatedAt(),
                    candidate.getDiscountType(), candidate.getDiscountValue(), distances.get(candidate.getCampaignId()),
                    budget.progressRate(), preferences.getOrDefault(candidate.getCategoryCode(), 0.0)));
        }
        return options;
    }

    private static boolean targets(AdCandidate candidate, MemberInfo member) {
        boolean gender = candidate.getTargetGender() == TargetGender.ALL
                || candidate.getTargetGender().name().equals(member.gender().name());
        return gender && List.of(candidate.getTargetAgeGroups().split(",")).contains(member.ageGroup().name());
    }

    // 그날 예산 계획이 없는 캠페인은 결과에서 빠진다. 예약이 없어 후보가 아니다
    private Map<Long, BudgetProgress> progress(List<Long> campaignIds, LocalDate businessDate, LocalDateTime now) {
        Map<Long, BudgetProgress> progress = new HashMap<>();
        for (int from = 0; from < campaignIds.size(); from += BudgetQueryService.MAX_CAMPAIGN_IDS) {
            List<Long> chunk = campaignIds.subList(from,
                    Math.min(from + BudgetQueryService.MAX_CAMPAIGN_IDS, campaignIds.size()));
            budgetQueryService.getProgress(chunk, businessDate, now)
                    .forEach(budget -> progress.put(budget.campaignId(), budget));
        }
        return progress;
    }

    private static boolean reachedImpressionCap(BudgetProgress budget, long validImpressions) {
        return validImpressions * budget.unitPrice() * 100 >= budget.reservedAmount() * IMPRESSION_CAP_PERCENT;
    }

    // 최근 30일 찜 가운데 그 업종의 비율이다. 찜이 없으면 모든 업종이 0 이다
    private Map<String, Double> preferences(Long memberId, LocalDate businessDate) {
        Map<String, Long> wishes = swipeLogRepository.countWishesByCategory(
                memberId, businessDate.minusDays(PREFERENCE_DAYS - 1), businessDate);
        long total = wishes.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Double> preferences = new HashMap<>();
        if (total > 0) {
            wishes.forEach((category, count) -> preferences.put(category, (double) count / total));
        }
        return preferences;
    }

    /*
     * serve 기록을 먼저 Valkey 에 쓰고 응답한다. 쓰기가 실패하면 피드를 실패시킨다. 캐시 기본 처리(통과)를 벗어난다.
     * 통과시키면 카드는 나가지만 그 카드의 노출이 전부 EXPIRED_OR_UNKNOWN 이 되어 점주에게 과금 없는 노출이 된다(#236).
     */
    private FeedResponse toServing(Long memberId, Feed feed, Map<Long, AdCandidate> candidates, Point point, LocalDate businessDate,
                                   LocalDateTime now, OffsetDateTime nextFeedAvailableAt) {
        Instant instant = now.atZone(ClockConfig.ZONE).toInstant();
        LocalDateTime servedAt = now.truncatedTo(ChronoUnit.MILLIS);
        UUID feedId = UuidV7.generate(instant);
        List<ServeRecord> records = new ArrayList<>();
        List<ServeLogRow> rows = new ArrayList<>();
        List<FeedCardResponse> cards = new ArrayList<>();
        for (Slot slot : feed.slots()) {
            AdCandidate candidate = candidates.get(slot.option().campaignId());
            UUID serveId = UuidV7.generate(instant);
            records.add(new ServeRecord(serveId, candidate.getCampaignId(), candidate.getStoreId(),
                    candidate.getCategoryCode(), slot.slotType(), slot.position(), servedAt));
            rows.add(new ServeLogRow(serveId, businessDate, feedId, memberId, candidate.getCampaignId(),
                    candidate.getStoreId(), slot.slotType(), slot.position(), servedAt));
            cards.add(FeedCardResponse.of(serveId, servedAt.atZone(ClockConfig.ZONE).toOffsetDateTime(), candidate,
                    (int) Math.round(slot.option().distanceM())));
        }
        Duration untilDayEnd = Duration.between(now, businessDate.plusDays(1).atStartOfDay());
        serveRecordRepository.save(businessDate, memberId, records, untilDayEnd);
        feedLogWriter.write(rows, businessDate, memberId, point.latitude(), point.longitude(), now);
        return FeedResponse.serving(feedId, feed.radiusM(), nextFeedAvailableAt, cards);
    }

    /*
     * 지금 바로 요청할 수 있으면 응답 시각을 초 단위로 돌려준다.
     * 아직이면 초 아래를 올려서, 알려 준 시각에 요청하면 반드시 받게 한다.
     */
    private static OffsetDateTime toOffset(Instant nextAvailableAt, Instant now) {
        Instant seconds = nextAvailableAt.truncatedTo(ChronoUnit.SECONDS);
        Instant shown = !nextAvailableAt.isAfter(now) || seconds.equals(nextAvailableAt) ? seconds : seconds.plusSeconds(1);
        return shown.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
