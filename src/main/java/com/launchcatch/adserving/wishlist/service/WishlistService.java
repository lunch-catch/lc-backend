package com.launchcatch.adserving.wishlist.service;

import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.wishlist.dto.WishlistItemResponse;
import com.launchcatch.adserving.wishlist.dto.WishlistResponse;
import com.launchcatch.adserving.wishlist.entity.Wishlist;
import com.launchcatch.adserving.wishlist.repository.WishlistRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 회원 본인의 찜 목록을 돌려준다(FR-071). 페이지네이션은 없다. 캠페인이 끝나면 찜이 빠져 진행 중인 캠페인 수만큼만 쌓인다.
 * 표시 값은 그날 후보 스냅샷에서 채우고 후보 행이 있으면 상태와 소진을 보지 않는다. 명세의 null 조건이 "오늘 노출 후보가 아닌 캠페인" 이다.
 */
@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final Clock clock;

    // campaignId 를 주면 그 캠페인의 찜만 본다. 찜하지 않았으면 빈 배열이다(#416)
    public WishlistResponse getWishlist(Long memberId, Long campaignId) {
        List<Wishlist> wishes = campaignId == null
                ? wishlistRepository.findByMemberIdOrderByCreatedAtDescIdDesc(memberId)
                : wishlistRepository.findByMemberIdAndCampaignId(memberId, campaignId).stream().toList();
        if (wishes.isEmpty()) {
            return new WishlistResponse(List.of());
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), ClockConfig.ZONE);
        Set<Long> campaignIds = wishes.stream().map(Wishlist::getCampaignId).collect(Collectors.toSet());
        Map<Long, AdCandidate> candidates = adCandidateRepository.findByIdBusinessDateAndIdCampaignIdIn(today, campaignIds)
                .stream().collect(Collectors.toMap(AdCandidate::getCampaignId, Function.identity()));
        return new WishlistResponse(wishes.stream()
                .map(wish -> WishlistItemResponse.of(wish, candidates.get(wish.getCampaignId())))
                .toList());
    }
}
