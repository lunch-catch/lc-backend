package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.CampaignPacing;
import com.launchcatch.adserving.engine.FeedAssembler;
import com.launchcatch.adserving.engine.FeedAssembler.Placed;
import com.launchcatch.adserving.engine.FeedAssembler.Selection;
import com.launchcatch.adserving.engine.FeedAssembler.Viewer;
import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedContext;
import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.exclusion.repository.ExclusionRepository;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.entity.FeedFilter;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.CategoryWishCount;
import com.launchcatch.adserving.feed.repository.FeedFilterCategoryRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository.Admission;
import com.launchcatch.adserving.feed.repository.ImpressionCountRepository;
import com.launchcatch.adserving.feed.repository.ServeFrequencyRepository;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.feed.repository.ServeFrequencyRepository.ServedHistory;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.id.UuidV7;
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
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    // 업종 선호도를 세는 기간이다. 오늘을 넣어 30일이다(#307).
    private static final int PREFERENCE_DAYS = 30;

    // 정산에서 예산을 읽지 못해 예산 규칙 없이 내보낸 횟수다. 경보는 이 지표에 건다.
    static final String BUDGET_UNAVAILABLE_METRIC = "adserving_budget_unavailable_total";

    // 응답으로 내려준 카드 수다. serve_log 건수와 대조해 서빙 로그 유실률을 잰다(feed-api-design.md 유실 목표).
    static final String SERVED_CARDS_METRIC = "adserving_served_cards_total";

    private final MemberQueryService memberQueryService;
    private final FeedRateLimitRepository feedRateLimitRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final ExclusionRepository exclusionRepository;
    private final FeedFilterCategoryRepository feedFilterCategoryRepository;
    private final FeedFilterRepository feedFilterRepository;
    private final SwipeLogRepository swipeLogRepository;
    private final ServeFrequencyRepository serveFrequencyRepository;
    private final ImpressionCountRepository impressionCountRepository;
    private final BudgetQueryService budgetQueryService;
    private final ServeRecordRepository serveRecordRepository;
    private final ServeLogBuffer serveLogBuffer;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 요청 제한은 온보딩과 위치를 확인한 뒤에 센다. 200 으로 끝나는 요청만 횟수에 들어가야 해서 403 과 422 는 세기 전에 거른다.
    public FeedResponse getFeed(Long memberId, FeedRequest request) {
        MemberInfo member = memberQueryService.findById(memberId)
                .filter(MemberInfo::onboardingCompleted)
                .orElseThrow(() -> new FeedException(FeedErrorCode.ONBOARDING_REQUIRED));
        Viewer viewer = viewerAt(member, request);

        Instant now = clock.instant();
        Admission admission = feedRateLimitRepository.tryAdmit(memberId, now);
        if (!admission.admitted()) {
            throw new FeedRateLimitedException(secondsUntil(admission.nextAvailableAt(), now));
        }
        OffsetDateTime nextFeedAvailableAt = admission.nextAvailableAt().atZone(ClockConfig.ZONE).toOffsetDateTime();

        ZonedDateTime servedAt = now.atZone(ClockConfig.ZONE);
        LocalDate businessDate = servedAt.toLocalDate();
        List<FeedCandidate> candidates = adCandidateRepository.findServableWithin(businessDate,
                        BoundingBox.around(viewer.latitude(), viewer.longitude(), FeedAssembler.MAX_RADIUS_M))
                .stream().map(AdCandidate::toFeedCandidate).toList();
        Selection selection = FeedAssembler.assemble(candidates,
                contextFor(memberId, viewer, businessDate, servedAt, candidates));
        // 카드가 없는 요청도 활동이라 세션을 늘리려고 빈 목록으로도 부른다
        serveFrequencyRepository.recordServed(businessDate, memberId,
                selection.cards().stream().map(p -> p.candidate().campaignId()).toList(), now);
        if (selection.cards().isEmpty()) {
            recordVisit(businessDate, memberId, viewer, servedAt);
            return FeedResponse.noCandidate(selection.radiusM(), nextFeedAvailableAt);
        }

        Instant servedInstant = now.truncatedTo(ChronoUnit.MILLIS);
        List<Placed> placed = selection.cards();
        List<ServeRecord> records = IntStream.range(0, placed.size())
                .mapToObj(i -> new ServeRecord(UuidV7.at(now), memberId, placed.get(i).candidate().campaignId(),
                        placed.get(i).candidate().storeId(), placed.get(i).slotType(), i + 1, servedInstant))
                .toList();
        // 노출이 곧바로 올 수 있어 응답 전에 쓴다. 서빙 로그는 판정에 쓰이지 않아 응답 뒤에 대기열이 쓴다
        serveRecordRepository.saveAll(records);
        meterRegistry.counter(SERVED_CARDS_METRIC).increment(records.size());
        UUID feedId = UuidV7.at(now);
        serveLogBuffer.offer(new ServedFeed(businessDate, memberId, feedId, viewer.latitude(), viewer.longitude(), records,
                servedAt.toLocalDateTime()));

        OffsetDateTime servedAtOffset = servedInstant.atZone(ClockConfig.ZONE).toOffsetDateTime();
        List<FeedCardResponse> cards = IntStream.range(0, placed.size())
                .mapToObj(i -> FeedCardResponse.of(records.get(i).serveId(), servedAtOffset,
                        placed.get(i).candidate(), placed.get(i).distanceM()))
                .toList();
        return FeedResponse.serving(feedId, selection.radiusM(), nextFeedAvailableAt, cards);
    }

    // 카드가 없는 요청도 그날 첫 접속 위치는 남긴다(feed_visit_daily).
    private void recordVisit(LocalDate businessDate, Long memberId, Viewer viewer, ZonedDateTime requestedAt) {
        serveLogBuffer.offer(new ServedFeed(businessDate, memberId, null, viewer.latitude(), viewer.longitude(), List.of(),
                requestedAt.toLocalDateTime()));
    }

    private FeedContext contextFor(Long memberId, Viewer viewer, LocalDate businessDate, ZonedDateTime servedAt,
                                   List<FeedCandidate> candidates) {
        ServedHistory history = serveFrequencyRepository.findHistory(businessDate, memberId);
        FeedSort sort = feedFilterRepository.findById(memberId).map(FeedFilter::getSortType).orElse(FeedSort.DISTANCE);
        List<CategoryWishCount> wishes = swipeLogRepository.countWishesByCategory(
                memberId, businessDate.minusDays(PREFERENCE_DAYS - 1), businessDate);
        return new FeedContext(
                viewer,
                exclusionRepository.findCampaignIds(businessDate, memberId),
                Set.copyOf(feedFilterCategoryRepository.findCategoryCodes(memberId)),
                sort,
                categoryPreference(wishes),
                history.dailyCounts(),
                history.sessionCampaignIds(),
                pacingFor(candidates.stream().map(FeedCandidate::campaignId).toList(), businessDate, servedAt));
    }

    /*
     * 정산의 진행률과 그날 유효 노출 수를 캠페인별로 묶는다. 정산 결과에 없는 캠페인은 예산 계획이 없어 빠진다.
     *
     * 정산이 예외를 던지면 null 을 돌려 예산 규칙을 건너뛴다. 장애 처리 가이드의 기본 처리(오류로 올린다)에서 벗어난다.
     * 서빙할 피드가 아예 없을 때만 오류로 답하고 그 밖에는 서빙을 이어 가기로 했다(#307). 차감은 정산이 따로 막아 점주가 더 내지 않는다.
     */
    private Map<Long, CampaignPacing> pacingFor(List<Long> campaignIds, LocalDate businessDate, ZonedDateTime servedAt) {
        List<BudgetProgress> progress;
        try {
            progress = readProgress(campaignIds, businessDate, servedAt);
        } catch (RuntimeException e) {
            log.warn("event=FEED_BUDGET_UNAVAILABLE 예산 규칙 없이 내보낸다", e);
            meterRegistry.counter(BUDGET_UNAVAILABLE_METRIC).increment();
            return null;
        }
        Map<Long, Long> validCounts = impressionCountRepository.findValidCounts(businessDate,
                progress.stream().map(BudgetProgress::campaignId).toList());
        return progress.stream().collect(Collectors.toMap(BudgetProgress::campaignId, p -> new CampaignPacing(
                p.progressRate(), p.reservedAmount(), p.unitPrice(), validCounts.getOrDefault(p.campaignId(), 0L))));
    }

    // 정산은 한 번에 MAX_CAMPAIGN_IDS 개까지 받는다. 그보다 많으면 나눠 부른다.
    private List<BudgetProgress> readProgress(List<Long> campaignIds, LocalDate businessDate, ZonedDateTime servedAt) {
        int chunk = BudgetQueryService.MAX_CAMPAIGN_IDS;
        List<BudgetProgress> all = new ArrayList<>();
        for (int from = 0; from < campaignIds.size(); from += chunk) {
            all.addAll(budgetQueryService.getProgress(
                    campaignIds.subList(from, Math.min(from + chunk, campaignIds.size())),
                    businessDate, servedAt.toLocalDateTime()));
        }
        return all;
    }

    // 업종 선호도는 기간 안의 찜 가운데 그 업종이 차지하는 비율이다. 찜이 없으면 비어 모든 업종이 0 이다.
    static Map<String, Double> categoryPreference(List<CategoryWishCount> counts) {
        long total = counts.stream().mapToLong(CategoryWishCount::count).sum();
        Map<String, Double> preference = new HashMap<>();
        counts.forEach(c -> preference.put(c.categoryCode(), (double) c.count() / total));
        return preference;
    }

    // 보낸 좌표가 있으면 그것을, 없으면 저장 위치를 쓴다(FR-064). 위치 동의는 보지 않는다(#236 에서 정했다).
    private static Viewer viewerAt(MemberInfo member, FeedRequest request) {
        if (request.hasLocation()) {
            return new Viewer(request.latitude(), request.longitude(), member.gender(), member.ageGroup());
        }
        if (member.latitude() == null || member.longitude() == null) {
            throw new FeedException(FeedErrorCode.LOCATION_REQUIRED);
        }
        return new Viewer(member.latitude().doubleValue(), member.longitude().doubleValue(),
                member.gender(), member.ageGroup());
    }

    // Retry-After 는 남은 시간을 초로 올림한다. 내림하면 알려 준 시각에 다시 와도 아직 창 안이다.
    private static long secondsUntil(Instant target, Instant now) {
        long millis = Duration.between(now, target).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
