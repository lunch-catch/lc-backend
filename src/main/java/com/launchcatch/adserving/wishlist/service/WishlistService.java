package com.launchcatch.adserving.wishlist.service;

import com.launchcatch.adserving.contract.WishlistQueryService;
import com.launchcatch.adserving.engine.entity.AdCandidate;
import com.launchcatch.adserving.engine.exception.CandidateErrorCode;
import com.launchcatch.adserving.engine.exception.CandidateException;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.exclusion.repository.ExclusionRepository;
import com.launchcatch.adserving.wishlist.dto.WishlistItemResponse;
import com.launchcatch.adserving.wishlist.dto.WishlistResponse;
import com.launchcatch.adserving.wishlist.entity.Wishlist;
import com.launchcatch.adserving.wishlist.repository.WishlistRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 회원 본인의 찜 목록을 돌려주고 가게 상세의 찜을 넣고 지운다(FR-071, FR-073). 페이지네이션은 없다. 캠페인이 끝나면 찜이 빠져 진행 중인 캠페인 수만큼만 쌓인다.
 * 표시 값은 그날 후보 스냅샷에서 채우고 후보 행이 있으면 상태와 소진을 보지 않는다. 명세의 null 조건이 "오늘 노출 후보가 아닌 캠페인" 이다.
 */
@Service
@RequiredArgsConstructor
public class WishlistService implements WishlistQueryService {

    private final WishlistRepository wishlistRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final ExclusionRepository exclusionRepository;
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

    /*
     * 가게 상세의 찜해두기다(FR-071). 그날 후보가 아니면 이미 찜한 캠페인이어도 거절한다(#420).
     * 찜을 먼저 쓰고 당일 이력에 넣는다. 당일 이력은 되돌릴 수 없어 먼저 넣으면 실패한 찜도 그날 피드에서 빠진다.
     * 이미 찜했어도 당일 이력에 다시 넣는다. 집합이라 그대로이고, 앞 요청이 Valkey 에 넣지 못했으면 여기서 채워진다.
     */
    public WishResult wish(Long memberId, Long campaignId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ClockConfig.ZONE);
        // 상태와 소진은 보지 않는다. 스와이프와 같다(#379)
        AdCandidate candidate = adCandidateRepository.findByIdBusinessDateAndIdCampaignId(today, campaignId)
                .orElseThrow(() -> new CandidateException(CandidateErrorCode.CAMPAIGN_NOT_SERVING));

        boolean created = wishlistRepository.insertIgnore(
                Wishlist.fromStoreDetail(memberId, campaignId, candidate.getStoreId()));
        exclusionRepository.add(today, memberId, campaignId, now);
        // 이미 찜했으면 처음 찜한 시각을 돌려줘야 해서 넣은 뒤 다시 읽는다
        Wishlist wish = wishlistRepository.findByMemberIdAndCampaignId(memberId, campaignId).orElseThrow();
        return new WishResult(created, WishlistItemResponse.of(wish, candidate));
    }

    /*
     * 찜 목록에서 지운다(FR-073). 찜하지 않은 캠페인이어도 거절하지 않는다.
     * 행을 먼저 지우고 당일 이력에 넣는다. 지우지 못한 찜을 그날 피드에서 빼지 않으려는 것이다.
     * 행이 없어도, 그날 후보가 아니어도 당일 이력에 넣는다. 앞 요청이 Valkey 에 넣지 못했으면 여기서 채워지고, 00:00 후보 적재 전에 지운 찜도 적재 뒤 그날 피드에 나오지 않는다(#547).
     */
    public void unwish(Long memberId, Long campaignId) {
        Instant now = clock.instant();
        wishlistRepository.deleteByMemberIdAndCampaignId(memberId, campaignId);
        exclusionRepository.add(LocalDate.ofInstant(now, ClockConfig.ZONE), memberId, campaignId, now);
    }

    // 두 조회 모두 찜 행이 있는지만 본다. 찜을 지우거나 캠페인이 끝나면 행이 지워진다(#163)
    @Override
    public boolean isWished(Long memberId, Long campaignId) {
        return wishlistRepository.existsByMemberIdAndCampaignId(memberId, campaignId);
    }

    @Override
    public List<Long> findWishedCampaignIds(Long memberId) {
        return wishlistRepository.findCampaignIdsByMemberId(memberId);
    }
}
