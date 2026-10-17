package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.exception.CandidateErrorCode;
import com.launchcatch.adserving.engine.exception.CandidateException;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.exclusion.repository.DailyExclusionRepository;
import com.launchcatch.adserving.feed.dto.SwipeRequest;
import com.launchcatch.adserving.feed.dto.SwipeResponse;
import com.launchcatch.adserving.feed.entity.SwipeAction;
import com.launchcatch.adserving.feed.entity.SwipeLog;
import com.launchcatch.adserving.feed.entity.SwipeLogId;
import com.launchcatch.adserving.feed.repository.SwipeLogRepository;
import com.launchcatch.adserving.wishlist.entity.Wishlist;
import com.launchcatch.adserving.wishlist.repository.WishlistRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 카드를 오른쪽(찜) 또는 왼쪽(패스)으로 넘긴다(FR-065). 같은 사용자의 같은 카드는 처음 넘긴 것 하나만 반영한다.
 * 찜과 스와이프 기록은 다른 애그리거트라 한 트랜잭션에 묶지 않고 쓰는 순서로 맞춘다(#379).
 * 찜을 먼저 쓴다. 기록이 실패해도 다시 보내면 찜은 넘어가고 기록만 들어간다. 반대 순서면 재시도가 반영되지 않은 것으로 끝나 찜이 빠진다.
 */
@Service
@RequiredArgsConstructor
public class SwipeService {

    private final SwipeLogRepository swipeLogRepository;
    private final WishlistRepository wishlistRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final DailyExclusionRepository dailyExclusionRepository;
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

        if (request.swipeAction() == SwipeAction.WISH) {
            wishlistRepository.insertIgnore(Wishlist.fromFeed(memberId, candidate.getCampaignId(), candidate.getStoreId(), serveId));
        }
        boolean applied = swipeLogRepository.insertIgnore(SwipeLog.of(serveId, memberId, today, candidate.getCampaignId(),
                candidate.getCategoryCode(), request.swipeAction(),
                request.occurredAt().atZoneSameInstant(ClockConfig.ZONE).toLocalDateTime()));
        if (!applied) {
            return new SwipeResponse(false);
        }
        // 기록이 커밋된 뒤에 넣는다. Valkey 는 되돌릴 수 없어 기록보다 먼저 넣으면 실패한 스와이프도 피드에서 빠진다
        dailyExclusionRepository.add(today, memberId, candidate.getCampaignId(), now);
        return new SwipeResponse(true);
    }
}
