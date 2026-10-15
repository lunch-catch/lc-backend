package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.FeedAssembler;
import com.launchcatch.adserving.engine.FeedAssembler.Selection;
import com.launchcatch.adserving.engine.FeedAssembler.Viewer;
import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.feed.UuidV7;
import com.launchcatch.adserving.feed.dto.FeedCardResponse;
import com.launchcatch.adserving.feed.dto.FeedRequest;
import com.launchcatch.adserving.feed.dto.FeedResponse;
import com.launchcatch.adserving.feed.exception.FeedErrorCode;
import com.launchcatch.adserving.feed.exception.FeedException;
import com.launchcatch.adserving.feed.exception.FeedRateLimitedException;
import com.launchcatch.adserving.feed.repository.DailyExclusionRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterCategoryRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository;
import com.launchcatch.adserving.feed.repository.FeedRateLimitRepository.Admission;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.geo.BoundingBox;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeedService {

    // 서빙 시간대는 10:00 부터 12:59 까지다. 13:00 은 밖이다.
    private static final LocalTime SERVING_START = LocalTime.of(10, 0);
    private static final LocalTime SERVING_END = LocalTime.of(13, 0);

    private final MemberQueryService memberQueryService;
    private final FeedRateLimitRepository feedRateLimitRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final DailyExclusionRepository dailyExclusionRepository;
    private final FeedFilterCategoryRepository feedFilterCategoryRepository;
    private final Clock clock;

    /*
     * 요청 제한은 온보딩과 위치를 확인한 뒤에 센다. 200 으로 끝나는 요청만 횟수에 들어가야 해서 403 과 422 는 세기 전에 거른다.
     * 서빙 시간대 밖의 요청도 200 이라 센다.
     */
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
        LocalTime time = servedAt.toLocalTime();
        if (time.isBefore(SERVING_START) || !time.isBefore(SERVING_END)) {
            return FeedResponse.outOfServingHours(nextFeedAvailableAt);
        }

        LocalDate businessDate = servedAt.toLocalDate();
        Selection selection = FeedAssembler.assemble(
                adCandidateRepository.findServableWithin(businessDate,
                                BoundingBox.around(viewer.latitude(), viewer.longitude(), FeedAssembler.MAX_RADIUS_M))
                        .stream().map(AdCandidate::toFeedCandidate).toList(),
                viewer,
                dailyExclusionRepository.findCampaignIds(businessDate, memberId),
                Set.copyOf(feedFilterCategoryRepository.findCategoryCodes(memberId)));
        if (selection.cards().isEmpty()) {
            return FeedResponse.noCandidate(selection.radiusM(), nextFeedAvailableAt);
        }

        OffsetDateTime servedAtOffset = servedAt.truncatedTo(ChronoUnit.MILLIS).toOffsetDateTime();
        List<FeedCardResponse> cards = selection.cards().stream()
                .map(p -> FeedCardResponse.of(UuidV7.at(now), servedAtOffset, p.candidate(), p.distanceM()))
                .toList();
        return FeedResponse.serving(UuidV7.at(now), selection.radiusM(), nextFeedAvailableAt, cards);
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
