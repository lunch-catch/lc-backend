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
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    // 끝난 찜을 끝난 날을 빼고 보여 주는 날 수다(#548)
    static final int ENDED_VISIBLE_DAYS = 3;

    private final WishlistRepository wishlistRepository;
    private final AdCandidateRepository adCandidateRepository;
    private final ExclusionRepository exclusionRepository;
    private final CampaignQueryService campaignQueryService;
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
        endIfCampaignEnded(campaignId);
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

    // 캠페인이 끝나면 그 캠페인의 찜을 지우지 않고 종료로 표시한다. 사용자가 끝난 것을 알 수 있게 3일 동안 남긴다(#548)
    public void endCampaign(Long campaignId, LocalDateTime endedAt) {
        wishlistRepository.markCampaignEnded(campaignId, endedAt, LocalDateTime.now(clock));
    }

    /*
     * 찜한 캠페인이 이미 끝났으면 종료로 표시한다. 그날 끝난 캠페인도 찜에 들어가는데(#379) 종료 이벤트는 다시 오지 않는다(#780).
     * 넣은 뒤에 읽는다. 넣기가 종료 표시의 잠금을 기다리므로, 넣기가 먼저 커밋되면 종료 표시가 그 행을 덮고 늦게 커밋되면 여기서 끝난 캠페인을 본다.
     * 이미 있던 찜에도 부른다. 앞 요청이 넣은 뒤 이 조회에서 실패했으면 다시 보낸 요청이 표시를 채운다.
     */
    public void endIfCampaignEnded(Long campaignId) {
        campaignQueryService.findAllById(List.of(campaignId)).stream()
                .filter(campaign -> campaign.status() == CampaignStatus.ENDED)
                .findFirst()
                .ifPresent(campaign -> endCampaign(campaignId, endedAt(campaign)));
    }

    // 종료 시각이 없으면 지금으로 둔다. 비워 두면 정리 작업이 지우지 않는다
    private LocalDateTime endedAt(CampaignInfo campaign) {
        return campaign.statusChangedAt() != null ? campaign.statusChangedAt() : LocalDateTime.now(clock);
    }

    /*
     * 끝난 날을 빼고 3일이 지난 찜을 지운다. 00:00 작업이 그 영업일로 부른다(#548).
     * 영업일 D 의 작업은 D-3 00:00 전에 끝난 찜을 지운다. 10/5 작업이면 10/1 까지 끝난 찜이고, 10/2 에 끝난 찜은 10/5 까지 보인다.
     */
    public void deleteExpiredEndedWishes(LocalDate businessDate) {
        wishlistRepository.deleteByCampaignEndedAtBefore(businessDate.minusDays(ENDED_VISIBLE_DAYS).atStartOfDay());
    }

    // 두 조회 모두 찜 행이 있고 캠페인이 끝나지 않은 것을 유효로 본다. 찜을 지우면 행이 지워지고 캠페인이 끝나면 종료로 표시된다(#163, #548)
    @Override
    public boolean isWished(Long memberId, Long campaignId) {
        return wishlistRepository.existsByMemberIdAndCampaignIdAndCampaignEndedAtIsNull(memberId, campaignId);
    }

    // 쿠폰 묶음 발급이 회원마다 isWished 를 부르지 않게 캠페인당 한 번 읽는다. 기준은 isWished 와 같다(#578)
    @Override
    public Set<Long> findWishedMemberIds(Long campaignId, Set<Long> memberIds) {
        if (campaignId == null || memberIds == null) {
            throw new IllegalArgumentException("캠페인 ID 와 회원 ID 집합이 있어야 한다");
        }
        if (memberIds.size() > MAX_WISHED_MEMBER_IDS) {
            throw new IllegalArgumentException("회원은 한 번에 " + MAX_WISHED_MEMBER_IDS + "명까지다: " + memberIds.size());
        }
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(wishlistRepository.findActiveMemberIds(campaignId, memberIds));
    }

    @Override
    public List<Long> findWishedCampaignIds(Long memberId) {
        return wishlistRepository.findActiveCampaignIdsByMemberId(memberId);
    }
}
