package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.config.PacingProperties;
import com.launchcatch.adserving.engine.BudgetPacing;
import com.launchcatch.adserving.engine.CampaignPacing;
import com.launchcatch.adserving.engine.FeedAssembler;
import com.launchcatch.adserving.engine.FeedAssembler.Placed;
import com.launchcatch.adserving.engine.FeedAssembler.Selection;
import com.launchcatch.adserving.engine.FeedAssembler.Viewer;
import com.launchcatch.adserving.engine.FeedCandidate;
import com.launchcatch.adserving.engine.FeedContext;
import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.service.CandidateCache;
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
import com.launchcatch.adserving.feed.repository.FeedStateRepository;
import com.launchcatch.adserving.feed.repository.FeedStateRepository.CachedFilter;
import com.launchcatch.adserving.feed.repository.FeedStateRepository.FeedState;
import com.launchcatch.adserving.feed.repository.ServeFrequencyRepository;
import com.launchcatch.adserving.feed.repository.ServeRecord;
import com.launchcatch.adserving.feed.repository.ServeRecordRepository;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.id.UuidV7;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.geo.BoundingBox;
import com.launchcatch.member.contract.MemberInfo;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeedService {

    // 업종 선호도를 세는 기간이다. 오늘을 넣어 30일이다(#307).
    private static final int PREFERENCE_DAYS = 30;

    // 응답으로 내려준 카드 수다. serve_log 건수와 대조해 서빙 로그 유실률을 잰다(feed-api-design.md 유실 목표).
    static final String SERVED_CARDS_METRIC = "adserving_served_cards_total";

    private final MemberInfoCache memberInfoCache;
    private final FeedRateLimitRepository feedRateLimitRepository;
    private final CandidateCache candidateCache;
    private final FeedStateRepository feedStateRepository;
    private final FeedFilterCategoryRepository feedFilterCategoryRepository;
    private final FeedFilterRepository feedFilterRepository;
    private final SwipeLogRepository swipeLogRepository;
    private final ServeFrequencyRepository serveFrequencyRepository;
    private final PacingProperties pacingProperties;
    private final ServeRecordRepository serveRecordRepository;
    private final ServeLogBuffer serveLogBuffer;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 요청 제한은 온보딩과 위치를 확인한 뒤에 센다. 200 으로 끝나는 요청만 횟수에 들어가야 해서 403 과 422 는 세기 전에 거른다.
    public FeedResponse getFeed(Long memberId, FeedRequest request) {
        MemberInfo member = memberInfoCache.find(memberId)
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
        List<AdCandidate> rows = candidateCache.findServableWithin(businessDate,
                BoundingBox.around(viewer.latitude(), viewer.longitude(), FeedAssembler.MAX_RADIUS_M));
        List<FeedCandidate> candidates = rows.stream().map(AdCandidate::toFeedCandidate).toList();
        Selection selection = FeedAssembler.assemble(candidates,
                contextFor(memberId, viewer, businessDate, servedAt, rows));
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
        List<ServedCard> servedCards = IntStream.range(0, placed.size())
                .mapToObj(i -> new ServedCard(records.get(i), placed.get(i).progressRate(), placed.get(i).relevanceScore()))
                .toList();
        serveLogBuffer.offer(new ServedFeed(businessDate, memberId, feedId, viewer.latitude(), viewer.longitude(),
                servedCards, FeedAssembler.ENGINE_VERSION, servedAt.toLocalDateTime()));

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
                FeedAssembler.ENGINE_VERSION, requestedAt.toLocalDateTime()));
    }

    /*
     * 하루와 세션 횟수, 당일 이력, 유효 노출 수, 필터, 30일 찜을 Valkey 에서 한 번에 읽는다(#1042 결정 5).
     * 필터와 30일 찜은 캐시라 없으면 DB 에서 읽어 채운다(#1042 결정 4).
     */
    private FeedContext contextFor(Long memberId, Viewer viewer, LocalDate businessDate, ZonedDateTime servedAt,
                                   List<AdCandidate> rows) {
        FeedState state = feedStateRepository.read(businessDate, memberId,
                rows.stream().map(AdCandidate::getCampaignId).toList());
        CachedFilter filter = state.filter() != null ? state.filter() : loadFilter(memberId);
        List<CategoryWishCount> wishes = state.wishes() != null ? state.wishes() : loadWishes(businessDate, memberId);
        return new FeedContext(
                businessDate,
                viewer,
                state.excludedCampaignIds(),
                filter.categoryCodes(),
                filter.sort(),
                categoryPreference(wishes),
                state.history().dailyCounts(),
                state.history().sessionCampaignIds(),
                pacingFor(rows, state.validCounts(), servedAt));
    }

    private CachedFilter loadFilter(Long memberId) {
        CachedFilter filter = new CachedFilter(
                feedFilterRepository.findById(memberId).map(FeedFilter::getSortType).orElse(FeedSort.DISTANCE),
                Set.copyOf(feedFilterCategoryRepository.findCategoryCodes(memberId)));
        feedStateRepository.cacheFilter(memberId, filter);
        return filter;
    }

    private List<CategoryWishCount> loadWishes(LocalDate businessDate, Long memberId) {
        List<CategoryWishCount> wishes = swipeLogRepository.countWishesByCategory(
                memberId, businessDate.minusDays(PREFERENCE_DAYS - 1), businessDate);
        feedStateRepository.cacheWishes(businessDate, memberId, wishes);
        return wishes;
    }

    /*
     * 후보에 적재된 예약액과 단가, 그리고 그날 유효 노출 수로 진행률을 계산해 캠페인별로 묶는다. 요청마다 정산을 부르지 않는다(#837).
     * 소진액은 유효 노출 수 x 단가로 본다. 과금하지 않은 유효 노출(예산 초과, 그날 계획 없음)도 섞이지만 대부분 상한 근처와 자정 직후에만 생긴다.
     * 예약액을 아직 읽지 못한 후보가 있으면 null 을 돌려 예산 규칙을 건너뛴다(#307). 읽지 못한 횟수는 CandidateBudgetService 가 센다.
     */
    private Map<Long, CampaignPacing> pacingFor(List<AdCandidate> rows, Map<Long, Long> validCounts, ZonedDateTime servedAt) {
        if (rows.stream().anyMatch(row -> row.getReservedAmount() == null)) {
            return null;
        }
        LocalTime at = servedAt.toLocalTime();
        Map<Long, CampaignPacing> pacing = new HashMap<>();
        for (AdCandidate row : rows) {
            long valid = validCounts.getOrDefault(row.getCampaignId(), 0L);
            long planned = BudgetPacing.plannedAmountUntil(row.getReservedAmount(), pacingProperties.hourlyWeights(), at);
            pacing.put(row.getCampaignId(), new CampaignPacing(BudgetPacing.progressRate(valid * row.getUnitPrice(), planned),
                    row.getReservedAmount(), row.getUnitPrice(), valid));
        }
        return pacing;
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
