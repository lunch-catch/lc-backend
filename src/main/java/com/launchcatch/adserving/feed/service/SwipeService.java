package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.contract.SwipeAction;
import com.launchcatch.adserving.contract.SwipedEvent;
import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.exception.CandidateErrorCode;
import com.launchcatch.adserving.engine.exception.CandidateException;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.exclusion.repository.ExclusionRepository;
import com.launchcatch.adserving.feed.dto.SwipeRequest;
import com.launchcatch.adserving.feed.dto.SwipeResponse;
import com.launchcatch.adserving.feed.entity.SwipeLog;
import com.launchcatch.adserving.feed.entity.SwipeLogId;
import com.launchcatch.adserving.feed.repository.FeedStateRepository;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.wishlist.entity.Wishlist;
import com.launchcatch.adserving.wishlist.repository.WishlistRepository;
import com.launchcatch.adserving.wishlist.service.WishlistService;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/*
 * 카드를 오른쪽(찜) 또는 왼쪽(패스)으로 넘긴다(FR-065). 같은 사용자의 같은 카드는 처음 넘긴 것 하나만 반영한다.
 * 찜과 스와이프 기록은 다른 애그리거트라 한 트랜잭션에 묶지 않고 쓰는 순서로 맞춘다(#379).
 * 찜을 먼저 쓴다. 기록이 실패해도 다시 보내면 찜은 넘어가고 기록만 들어간다. 반대 순서면 재시도가 반영되지 않은 것으로 끝나 찜이 빠진다.
 */
@Service
@RequiredArgsConstructor
public class SwipeService {

    // 같은 스와이프가 다시 처리돼도 분석이 하나로 거르게 스와이프의 중복 키 (serveId, 사용자) 로 만든다.
    private static final String EVENT_ID_PREFIX = "swipe:";

    private final SwipeLogRepository swipeLogRepository;
    private final WishlistRepository wishlistRepository;
    private final WishlistService wishlistService;
    private final AdCandidateRepository adCandidateRepository;
    private final ExclusionRepository exclusionRepository;
    private final FeedStateRepository feedStateRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public SwipeResponse swipe(Long memberId, SwipeRequest request) {
        UUID serveId = UUID.fromString(request.serveId());
        // 이미 넘긴 카드는 찜을 쓰기 전에 거른다. 먼저 패스한 카드를 찜으로 다시 보내도 찜에 들어가지 않는다
        if (swipeLogRepository.existsById(SwipeLogId.of(serveId, memberId))) {
            return new SwipeResponse(false);
        }

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ClockConfig.ZONE);
        // 상태와 소진은 보지 않는다. 카드를 본 뒤 캠페인이 멈춰도 사용자의 스와이프는 남긴다(#379)
        AdCandidate candidate = adCandidateRepository.findByIdBusinessDateAndIdCampaignId(today, request.campaignId())
                .orElseThrow(() -> new CandidateException(CandidateErrorCode.CAMPAIGN_NOT_SERVING));

        Long wishId = null;
        boolean wishCreated = false;
        if (request.swipeAction() == SwipeAction.WISH) {
            wishCreated = wishlistRepository.insertIgnore(
                    Wishlist.fromFeed(memberId, candidate.getCampaignId(), candidate.getStoreId(), serveId));
            wishlistService.endIfCampaignEnded(candidate.getCampaignId());
            /*
             * 이미 찜한 캠페인이면 넣기가 무시되므로 다시 읽어 그 찜의 id 를 싣는다.
             * 그사이 같은 사용자의 찜 삭제가 커밋됐으면 읽을 찜이 없어 id 없이 이어 간다(#783).
             */
            wishId = wishlistRepository.findByMemberIdAndCampaignId(memberId, candidate.getCampaignId())
                    .map(Wishlist::getId).orElse(null);
        }
        SwipeLog log = SwipeLog.of(serveId, memberId, today, candidate.getCampaignId(), candidate.getCategoryCode(),
                request.swipeAction(), request.occurredAt().atZoneSameInstant(ClockConfig.ZONE).toLocalDateTime());
        if (!swipeLogRepository.insertIgnore(log)) {
            /*
             * 같은 카드의 다른 스와이프가 기록을 먼저 넣었다. 그쪽이 PASS 면 이 요청이 넣은 찜이 패스한 카드에 남는다.
             * 이 요청이 새로 넣은 찜만 지운다. 이미 있던 찜은 다른 카드나 가게 상세에서 넣은 것이다(#783).
             */
            if (wishCreated) {
                wishlistRepository.deleteByMemberIdAndCampaignId(memberId, candidate.getCampaignId());
            }
            return new SwipeResponse(false);
        }
        // 기록이 커밋된 뒤에 넣는다. Valkey 는 되돌릴 수 없어 기록보다 먼저 넣으면 실패한 스와이프도 피드에서 빠진다
        exclusionRepository.add(today, memberId, candidate.getCampaignId(), now);
        // 30일 찜이 바뀌었다. 피드의 업종 선호도 캐시를 지워 다음 피드가 DB 에서 다시 세게 한다(#1042 결정 4)
        if (request.swipeAction() == SwipeAction.WISH) {
            feedStateRepository.evictWishes(today, memberId);
        }
        /*
         * 분석에 넘길 일을 커밋 전에 기록해 두지 않는다(장애 처리 가이드 "롤백 안 되는 효과"의 기본을 벗어난다).
         * 퍼널은 유실을 허용하고 스와이프 수는 swipe_log 로 다시 센다(아키텍처 설계 2.4).
         * 트랜잭션 밖에서 기록이 이미 커밋된 뒤라 수신자는 fallbackExecution 을 켜야 받는다.
         */
        eventPublisher.publishEvent(new SwipedEvent(EVENT_ID_PREFIX + serveId + ":" + memberId, log.getOccurredAt(),
                memberId, candidate.getCampaignId(), candidate.getStoreId(), serveId, request.swipeAction(), wishId));
        return new SwipeResponse(true);
    }
}
