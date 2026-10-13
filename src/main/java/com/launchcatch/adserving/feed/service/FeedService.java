package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.BudgetSnapshot;
import com.launchcatch.adserving.engine.CategoryPreference;
import com.launchcatch.adserving.engine.FeedAssembler;
import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedInput;
import com.launchcatch.adserving.engine.FeedPlan;
import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.engine.PlannedCard;
import com.launchcatch.adserving.engine.Viewer;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.ServeIds;
import com.launchcatch.adserving.feed.ServeRecord;
import com.launchcatch.adserving.feed.SwipeHistory;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedResponse;
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
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.geo.BoundingBox;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/*
 * GET /v1/feed 를 처리한다. 처리 순서와 정한 이유는 feed-api-design.md 가 갖는다.
 *
 * 트랜잭션을 걸지 않는다. 읽기만 하고 그 읽기들은 서로 같은 시점일 필요가 없다.
 * 쓰기(serve 기록, 서빙 로그, 접속 위치)는 ServeRecordService 가 응답과 떼어 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    static final String CACHE_BYPASS_METRIC = "adserving.feed.cache.bypass";

    /*
     * 서빙 시간대 10:00 ~ 12:59 다.
     * 끝은 13:00 직전까지다(67행)
     */
    private static final LocalTime SERVING_START = LocalTime.of(10, 0);
    private static final LocalTime SERVING_END = LocalTime.of(13, 0);

    /*
     * 같은 캠페인을 한 사용자에게 하루에 내려주는 최대 횟수다(비즈니스 규칙).
     * ops 가 설정값을 공개하면 거기서 읽는다(#187)
     */
    private static final int DAILY_SERVE_CAP = 2;

    /*
     * 카테고리 선호도를 세는 기간이다(97행).
     * 오늘을 포함해 30일이다
     */
    private static final int PREFERENCE_DAYS = 30;

    private final MemberQueryService memberQueryService;

    /*
     * 정산의 구현이 아직 develop 에 없어(#193) 빈이 없을 수 있다. 없으면 예산 조건 없이 배정한다.
     * #193 이 병합되면 직접 주입으로 바꾼다.
     */
    private final ObjectProvider<BudgetQueryService> budgetQueryService;

    private final AdCandidateRepository adCandidateRepository;
    private final FeedFilterRepository feedFilterRepository;
    private final SwipeLogRepository swipeLogRepository;
    private final ImpressionLogRepository impressionLogRepository;
    private final FeedRequestLimitRepository feedRequestLimitRepository;
    private final ServedHistoryRepository servedHistoryRepository;
    private final ServeRecordService serveRecordService;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final FeedAssembler feedAssembler = new FeedAssembler();

    public FeedResponse getFeed(Long memberId, Double latitude, Double longitude) {
        MemberInfo member = memberQueryService.findById(memberId)
                .filter(MemberInfo::onboardingCompleted)
                .orElseThrow(() -> new FeedException(FeedErrorCode.ONBOARDING_REQUIRED));
        Viewer viewer = viewer(member, latitude, longitude);

        Instant now = clock.instant();
        LocalDateTime localNow = LocalDateTime.ofInstant(now, ClockConfig.ZONE);
        LocalDate businessDate = localNow.toLocalDate();
        FeedRequestPermit permit = acquire(memberId, now);
        try {
            return respond(memberId, viewer, businessDate, localNow, toOffset(permit.nextAvailableAt()));
        } catch (RuntimeException e) {
            release(memberId, permit.requestId());
            throw e;
        }
    }

    private FeedResponse respond(Long memberId, Viewer viewer, LocalDate businessDate, LocalDateTime localNow,
                                 OffsetDateTime nextFeedAvailableAt) {
        serveRecordService.recordVisit(memberId, businessDate, viewer.latitude(), viewer.longitude());

        if (!inServingHours(localNow.toLocalTime())) {
            return FeedResponse.outOfServingHours(nextFeedAvailableAt);
        }

        FeedPlan plan = feedAssembler.assemble(input(memberId, viewer, businessDate, localNow));
        if (plan.cards().isEmpty()) {
            return FeedResponse.noCandidate(plan.radiusM(), nextFeedAvailableAt);
        }
        return serve(memberId, businessDate, localNow, plan, nextFeedAvailableAt);
    }

    // 요청 위치가 있으면 그것을, 없으면 저장 위치를 쓴다(67행 "위치 획득에 실패하면 마지막 수동 입력 위치 사용")
    private static Viewer viewer(MemberInfo member, Double latitude, Double longitude) {
        if (latitude != null && longitude != null) {
            return new Viewer(latitude, longitude, member.gender(), member.ageGroup());
        }
        if (member.latitude() == null || member.longitude() == null) {
            throw new FeedException(FeedErrorCode.LOCATION_REQUIRED);
        }
        return new Viewer(member.latitude().doubleValue(), member.longitude().doubleValue(),
                member.gender(), member.ageGroup());
    }

    /*
     * 요청 횟수를 세고 판정을 돌려준다. 앞의 검증을 통과한 요청만 센다.
     * 캐시가 멈추면 세지 못할 뿐이라 통과시키고 센다(장애 처리 가이드의 캐시 기본 처리).
     */
    private FeedRequestPermit acquire(Long memberId, Instant now) {
        FeedRequestPermit permit;
        try {
            permit = feedRequestLimitRepository.tryAcquire(memberId, now);
        } catch (DataAccessException e) {
            bypassed("rate_limit", memberId, e);
            return new FeedRequestPermit(true, now, null);
        }
        if (!permit.allowed()) {
            throw new FeedRateLimitedException(retryAfterSeconds(now, permit.nextAvailableAt()));
        }
        return permit;
    }

    /*
     * 200 으로 끝나지 않은 요청을 횟수에서 뺀다(feed-api-spec.md 의 요청 제한).
     * 빼지 못해도 원래 실패를 가리지 않는다. 그 사용자의 창에 한 번이 더 남을 뿐이라 통과시키고 센다.
     */
    private void release(Long memberId, String requestId) {
        if (requestId == null) {
            return;
        }
        try {
            feedRequestLimitRepository.release(memberId, requestId);
        } catch (DataAccessException e) {
            bypassed("rate_limit_release", memberId, e);
        }
    }

    /*
     * 남은 초를 올림한다.
     * 0 이하로 내려가면 곧바로 다시 몰리므로 1초 아래로 주지 않는다
     */
    private static long retryAfterSeconds(Instant now, Instant retryAt) {
        long millis = Duration.between(now, retryAt).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    private static boolean inServingHours(LocalTime time) {
        return !time.isBefore(SERVING_START) && time.isBefore(SERVING_END);
    }

    private FeedInput input(Long memberId, Viewer viewer, LocalDate businessDate, LocalDateTime localNow) {
        BoundingBox box = BoundingBox.around(viewer.latitude(), viewer.longitude(), FeedAssembler.MAX_SEARCH_RADIUS_M);
        List<FeedCandidate> candidates = adCandidateRepository.findServable(businessDate, box);
        List<SwipeHistory> swipes =
                swipeLogRepository.findByMemberSince(memberId, businessDate.minusDays(PREFERENCE_DAYS - 1));
        FeedSort sort = feedFilterRepository.findSort(memberId).orElse(FeedSort.DISTANCE);
        Set<String> categories = Set.copyOf(feedFilterRepository.findCategories(memberId));

        return new FeedInput(viewer, businessDate, candidates, categories, sort,
                excluded(memberId, businessDate, swipes), preference(swipes),
                budget(candidates, businessDate, localNow));
    }

    // 그날 찜하거나 패스한 캠페인(102행 (9))과 하루 2회, 세션 1회에 걸린 캠페인이다
    private Set<Long> excluded(Long memberId, LocalDate businessDate, List<SwipeHistory> swipes) {
        Set<Long> excluded = new HashSet<>();
        swipes.stream()
                .filter(swipe -> swipe.businessDate().equals(businessDate))
                .forEach(swipe -> excluded.add(swipe.campaignId()));

        ServedHistory history = servedHistory(memberId, businessDate);
        history.todayCounts().forEach((campaignId, count) -> {
            if (count >= DAILY_SERVE_CAP) {
                excluded.add(campaignId);
            }
        });
        excluded.addAll(history.sessionCampaignIds());
        return excluded;
    }

    /*
     * 캐시가 멈추면 이력 없이 배정한다.
     * 상한을 한 번 더 열어 줄 뿐이라 피드를 막지 않는다
     */
    private ServedHistory servedHistory(Long memberId, LocalDate businessDate) {
        try {
            return servedHistoryRepository.find(memberId, businessDate);
        } catch (DataAccessException e) {
            bypassed("served_history", memberId, e);
            return ServedHistory.EMPTY;
        }
    }

    private static CategoryPreference preference(List<SwipeHistory> swipes) {
        return CategoryPreference.fromWishedCategories(swipes.stream()
                .filter(swipe -> swipe.action() == SwipeAction.WISH)
                .map(SwipeHistory::categoryCode)
                .toList());
    }

    /*
     * 진행률은 정산에서, 유효 노출 수는 자기 노출 로그에서 읽는다(feed-api-design.md).
     * 조회가 실패하면 그대로 올려 피드를 실패시킨다. 예산 없이 내려주면 상한에 닿은 캠페인이 과금 없이 노출된다(#187).
     */
    private BudgetSnapshot budget(List<FeedCandidate> candidates, LocalDate businessDate, LocalDateTime localNow) {
        BudgetQueryService budgets = budgetQueryService.getIfAvailable();
        if (budgets == null || candidates.isEmpty()) {
            return BudgetSnapshot.UNKNOWN;
        }
        List<Long> campaignIds = candidates.stream().map(FeedCandidate::campaignId).toList();
        Map<Long, BudgetProgress> progress = new HashMap<>();
        for (int from = 0; from < campaignIds.size(); from += BudgetQueryService.MAX_CAMPAIGN_IDS) {
            List<Long> chunk = campaignIds.subList(from, Math.min(from + BudgetQueryService.MAX_CAMPAIGN_IDS, campaignIds.size()));
            budgets.getProgress(chunk, businessDate, localNow)
                    .forEach(budget -> progress.put(budget.campaignId(), budget));
        }
        return BudgetSnapshot.of(progress, impressionLogRepository.countValidByCampaign(businessDate, progress.keySet()));
    }

    private FeedResponse serve(Long memberId, LocalDate businessDate, LocalDateTime localNow, FeedPlan plan,
                               OffsetDateTime nextFeedAvailableAt) {
        LocalDateTime servedAt = localNow.truncatedTo(ChronoUnit.MILLIS);
        Instant servedInstant = servedAt.atZone(ClockConfig.ZONE).toInstant();
        OffsetDateTime servedAtOffset = toOffset(servedInstant);
        UUID feedId = ServeIds.next(servedInstant);

        List<ServeRecord> records = new ArrayList<>();
        List<FeedCardResponse> cards = new ArrayList<>();
        for (PlannedCard card : plan.cards()) {
            UUID serveId = ServeIds.next(servedInstant);
            records.add(new ServeRecord(serveId, feedId, memberId, businessDate, card.candidate().campaignId(),
                    card.candidate().storeId(), card.slotType(), card.slotPosition(), servedAt));
            cards.add(FeedCardResponse.of(serveId, servedAtOffset, card.candidate(), card.distanceM()));
        }
        serveRecordService.record(records);
        return FeedResponse.serving(feedId, plan.radiusM(), nextFeedAvailableAt, cards);
    }

    private void bypassed(String target, Long memberId, DataAccessException e) {
        meterRegistry.counter(CACHE_BYPASS_METRIC, "target", target).increment();
        log.warn("event=FEED_CACHE_BYPASSED target={} memberId={} 통과시킨다", target, memberId, e);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atZone(ClockConfig.ZONE).toOffsetDateTime();
    }
}
