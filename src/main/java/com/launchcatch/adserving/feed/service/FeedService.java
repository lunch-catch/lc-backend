package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.FeedAssembler;
import com.launchcatch.adserving.engine.FeedAssembly;
import com.launchcatch.adserving.engine.FeedFilter;
import com.launchcatch.adserving.engine.NearbyCandidate;
import com.launchcatch.adserving.engine.ServableCandidate;
import com.launchcatch.adserving.engine.SlotCard;
import com.launchcatch.adserving.engine.repository.AdCandidateQueryRepository;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.UuidV7;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedResponse;
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
import com.launchcatch.global.geo.BoundingBox;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * 스와이프 피드 한 묶음을 만든다.
 * 후보는 그날 스냅샷(ad_candidate)에서만 찾고 서빙 중에 다른 도메인 테이블을 읽지 않는다(96행).
 *
 * Valkey 에 기대는 세 자리(요청 제한, 하루와 세션 횟수, serve 기록)는 멈추면 통과시키고 센다.
 * 캐시 장애가 피드 전면 장애가 되지 않게 하는 캐시의 기본 처리다.
 * 정산의 진행률 조회는 실패하면 그대로 올린다. 금전 판정이라 모르는 채로 카드를 내려주지 않는다.
 */
@Service
@RequiredArgsConstructor
public class FeedService {

    static final String CACHE_BYPASS_METRIC = "adserving_feed_cache_bypass_total";

    private static final int RATE_LIMIT = 6;
    private static final Duration RATE_WINDOW = Duration.ofSeconds(60);
    private static final LocalTime SERVING_START = LocalTime.of(10, 0);
    private static final LocalTime SERVING_END = LocalTime.of(13, 0);
    private static final int DAILY_SERVE_CAP = 2;
    private static final int PREFERENCE_DAYS = 30;
    private static final int LOCATION_SCALE = 3;
    private static final String ISSUE_OPEN_TIME = "11:00";
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final MemberQueryService memberQueryService;
    private final BudgetQueryService budgetQueryService;
    private final FeedRateLimitRepository rateLimitRepository;
    private final ServeHistoryRepository serveHistoryRepository;
    private final AdCandidateQueryRepository adCandidateRepository;
    private final FeedFilterQueryRepository feedFilterRepository;
    private final SwipeLogQueryRepository swipeLogRepository;
    private final ImpressionLogQueryRepository impressionLogRepository;
    private final ServeLogWriter serveLogWriter;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 위치는 둘 다 오거나 둘 다 오지 않는다. 짝은 요청 타입이 검증한다
    public FeedResponse getFeed(Long memberId, Double latitude, Double longitude) {
        MemberInfo member = memberQueryService.findById(memberId)
                .filter(MemberInfo::onboardingCompleted)
                .orElseThrow(() -> new FeedException(FeedErrorCode.ONBOARDING_REQUIRED));
        Location location = resolveLocation(member, latitude, longitude);

        ZonedDateTime now = ZonedDateTime.now(clock);
        OffsetDateTime nextFeedAvailableAt = acquire(memberId, now);
        LocalDate today = now.toLocalDate();
        BigDecimal latRounded = round(location.latitude());
        BigDecimal lngRounded = round(location.longitude());

        LocalTime time = now.toLocalTime();
        if (time.isBefore(SERVING_START) || !time.isBefore(SERVING_END)) {
            serveLogWriter.write(memberId, today, List.of(), latRounded, lngRounded);
            return FeedResponse.outOfServingHours(nextFeedAvailableAt);
        }

        FeedAssembly assembly = assemble(member, location, now.toLocalDateTime());
        if (assembly.cards().isEmpty()) {
            serveLogWriter.write(memberId, today, List.of(), latRounded, lngRounded);
            return FeedResponse.noCandidate(assembly.radiusM(), nextFeedAvailableAt);
        }

        UUID feedId = UuidV7.generate(now.toInstant());
        List<ServeRecord> records = assembly.cards().stream()
                .map(card -> toRecord(card, feedId, memberId, now))
                .toList();
        recordServes(memberId, today, records);
        serveLogWriter.write(memberId, today, records, latRounded, lngRounded);

        List<FeedCardResponse> cards = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            cards.add(toCard(assembly.cards().get(i).nearby(), records.get(i), now.toOffsetDateTime()));
        }
        return FeedResponse.serving(feedId, assembly.radiusM(), nextFeedAvailableAt, cards);
    }

    private Location resolveLocation(MemberInfo member, Double latitude, Double longitude) {
        if (latitude != null) {
            return new Location(latitude, longitude);
        }
        if (member.latitude() == null || member.longitude() == null) {
            throw new FeedException(FeedErrorCode.LOCATION_REQUIRED);
        }
        return new Location(member.latitude().doubleValue(), member.longitude().doubleValue());
    }

    /*
     * 최근 60초 안의 요청이 6회 미만이면 이번 요청을 넣고 다음 피드 시각을 돌려준다.
     * 다음 피드 시각과 Retry-After 는 같은 계산이다. 가장 오래된 요청 시각에 60초를 더한 때다.
     */
    private OffsetDateTime acquire(Long memberId, ZonedDateTime now) {
        RateLimitResult result;
        try {
            result = rateLimitRepository.tryAcquire(memberId, now.toInstant(), RATE_LIMIT, RATE_WINDOW);
        } catch (DataAccessException e) {
            countBypass("rate_limit");
            return now.toOffsetDateTime();
        }
        ZonedDateTime freedAt = result.oldest().plus(RATE_WINDOW).atZone(now.getZone());
        if (!result.allowed()) {
            long remainingMillis = Duration.between(now, freedAt).toMillis();
            throw new FeedRateLimitedException(Math.ceilDiv(remainingMillis, 1000L));
        }
        return result.count() < RATE_LIMIT ? now.toOffsetDateTime() : freedAt.toOffsetDateTime();
    }

    private FeedAssembly assemble(MemberInfo member, Location location, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        Long memberId = member.memberId();
        List<ServableCandidate> candidates = adCandidateRepository.findServable(today,
                BoundingBox.around(location.latitude(), location.longitude(), FeedAssembler.MAX_SEARCH_RADIUS_M));
        FeedFilter filter = feedFilterRepository.find(memberId);

        Set<Long> excluded = new HashSet<>(swipeLogRepository.findSwipedCampaignIds(memberId, today));
        ServeHistory history = findHistory(memberId, today);
        history.todayCounts().forEach((campaignId, count) -> {
            if (count >= DAILY_SERVE_CAP) {
                excluded.add(campaignId);
            }
        });
        excluded.addAll(history.sessionCampaignIds());

        List<NearbyCandidate> eligible = FeedAssembler.eligible(candidates, location.latitude(), location.longitude(),
                member.gender(), member.ageGroup(), filter, excluded);
        if (eligible.isEmpty()) {
            return FeedAssembler.assemble(eligible, Map.of(), Map.of(), Map.of(), filter.sort());
        }
        List<Long> campaignIds = eligible.stream().map(NearbyCandidate::campaignId).toList();
        Map<String, Long> wishes = swipeLogRepository.countWishesByCategory(memberId,
                today.minusDays(PREFERENCE_DAYS - 1L), today);
        return FeedAssembler.assemble(eligible, findProgress(campaignIds, today, now),
                impressionLogRepository.countValidByCampaign(today, campaignIds), wishes, filter.sort());
    }

    private ServeHistory findHistory(Long memberId, LocalDate today) {
        try {
            return serveHistoryRepository.find(memberId, today);
        } catch (DataAccessException e) {
            countBypass("history_read");
            return ServeHistory.EMPTY;
        }
    }

    // 정산은 한 번에 받는 캠페인 수에 상한이 있어 나눠 부른다
    private Map<Long, BudgetProgress> findProgress(List<Long> campaignIds, LocalDate today, LocalDateTime now) {
        Map<Long, BudgetProgress> progress = new HashMap<>();
        for (int from = 0; from < campaignIds.size(); from += BudgetQueryService.MAX_CAMPAIGN_IDS) {
            List<Long> chunk = campaignIds.subList(from,
                    Math.min(from + BudgetQueryService.MAX_CAMPAIGN_IDS, campaignIds.size()));
            for (BudgetProgress each : budgetQueryService.getProgress(chunk, today, now)) {
                progress.put(each.campaignId(), each);
            }
        }
        return progress;
    }

    private void recordServes(Long memberId, LocalDate today, List<ServeRecord> records) {
        try {
            serveHistoryRepository.record(memberId, today, records);
        } catch (DataAccessException e) {
            countBypass("history_write");
        }
    }

    private static ServeRecord toRecord(SlotCard card, UUID feedId, Long memberId, ZonedDateTime now) {
        ServableCandidate candidate = card.nearby().candidate();
        return new ServeRecord(UuidV7.generate(now.toInstant()), feedId, memberId, now.toLocalDate(),
                candidate.campaignId(), candidate.storeId(), card.slotType(), card.slotPosition(),
                now.toLocalDateTime());
    }

    private static FeedCardResponse toCard(NearbyCandidate nearby, ServeRecord record, OffsetDateTime servedAt) {
        ServableCandidate candidate = nearby.candidate();
        return new FeedCardResponse(
                record.serveId(),
                servedAt,
                candidate.campaignId(),
                candidate.storeId(),
                candidate.storeName(),
                candidate.categoryCode(),
                nearby.distanceM(),
                new FeedCardResponse.Discount(candidate.discountTargetType(), candidate.discountType(),
                        candidate.discountValue()),
                ISSUE_OPEN_TIME,
                candidate.usableStartTime().format(HOUR_MINUTE),
                candidate.usableEndTime().format(HOUR_MINUTE),
                candidate.issueQuantity(),
                "/v1/campaigns/" + candidate.campaignId() + "/poster");
    }

    // 접속 위치는 소수점 3자리(약 100m)로 반올림해 남긴다
    private static BigDecimal round(double coordinate) {
        return BigDecimal.valueOf(coordinate).setScale(LOCATION_SCALE, RoundingMode.HALF_UP);
    }

    private void countBypass(String operation) {
        meterRegistry.counter(CACHE_BYPASS_METRIC, "operation", operation).increment();
    }

    private record Location(double latitude, double longitude) {
    }
}
