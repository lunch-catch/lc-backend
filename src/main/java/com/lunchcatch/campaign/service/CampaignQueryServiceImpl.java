package com.lunchcatch.campaign.service;

import com.lunchcatch.campaign.contract.CampaignInfo;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.entity.Campaign;
import com.lunchcatch.campaign.repository.CampaignRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CampaignQueryServiceImpl implements CampaignQueryService {

    static final int MAX_SERVABLE_CAMPAIGNS = 10000;

    /*
     * "집행 중" = DRAFT, ENDED가 아닌 상태 전부. hasLiveCampaign과
     * findOwnerIdsWithLiveCampaign이 이 상수 하나를 공유해서, 조건이 두 곳에 흩어져 한쪽만
     * 바뀌면 어긋나는 것을 막는다(#198, 정산의 환불 거부 조건).
     */
    private static final List<CampaignStatus> NOT_LIVE_STATUSES =
            List.of(CampaignStatus.DRAFT, CampaignStatus.ENDED);

    private final CampaignRepository campaignRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    @Override
    public List<CampaignInfo> findByStoreIdIn(List<Long> storeIds) {
        return campaignRepository.findByStoreIdIn(storeIds).stream().map(this::toInfo).toList();
    }

    @Override
    public List<CampaignInfo> findAllById(List<Long> campaignIds) {
        return campaignRepository.findAllById(campaignIds).stream().map(this::toInfo).toList();
    }

    @Override
    public List<CampaignInfo> findServableOn(LocalDate businessDate) {
        List<Campaign> campaigns = campaignRepository.findServableOn(
                businessDate, PageRequest.of(0, MAX_SERVABLE_CAMPAIGNS + 1));
        if (campaigns.size() > MAX_SERVABLE_CAMPAIGNS) {
            throw new IllegalStateException(
                    "findServableOn result must not exceed " + MAX_SERVABLE_CAMPAIGNS + " rows: "
                            + campaigns.size());
        }
        return campaigns.stream().map(this::toInfo).toList();
    }

    @Override
    public List<CampaignInfo> findEligibleCampaigns() {
        LocalDate today = LocalDate.now(clock);
        return campaignRepository.findEligibleCampaigns(today).stream().map(this::toInfo).toList();
    }

    @Override
    public List<CampaignInfo> findByOwnerIdAndStatusIn(Long ownerId, List<CampaignStatus> statuses) {
        return campaignRepository.findByOwnerIdAndStatusIn(ownerId, statuses).stream()
                .map(this::toInfo).toList();
    }

    @Override
    public boolean hasLiveCampaign(Long ownerId) {
        return campaignRepository.existsByOwnerIdAndStatusNotIn(ownerId, NOT_LIVE_STATUSES);
    }

    @Override
    public Set<Long> findOwnerIdsWithLiveCampaign(Collection<Long> ownerIds) {
        if (ownerIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(campaignRepository.findDistinctOwnerIdsWithLiveCampaign(ownerIds, NOT_LIVE_STATUSES));
    }

    /*
     * 호출자가 같은 트랜잭션에서 이 캠페인을 findByIdForUpdate보다 먼저 읽었다면(예:
     * findByOwnerIdAndStatusIn으로 후보를 먼저 조회), 그 Campaign은 이미 영속성 컨텍스트에
     * 있다. 그 상태에서 이 메서드를 호출하면 FOR UPDATE SQL은 실행되고 행 잠금은 걸리지만,
     * Hibernate가 이미 관리 중인 엔티티를 그대로 돌려줘 최신 컬럼 값으로 덮어쓰지 않는다.
     * refresh(PESSIMISTIC_WRITE)로 강제로 다시 읽어야 "잠그고 최신 상태를 읽는다"는 이
     * 메서드의 계약이 실제로 지켜진다(코드래빗 리뷰로 찾음, #378).
     *
     * 행 잠금은 트랜잭션이 끝날 때 풀린다. 트랜잭션 없이 부르면 잠금이 이 호출 안에서 바로 풀려 잠갔다는 보장이 없으므로
     * MANDATORY 로 거부한다(#876). store 의 StoreCampaignSummaryUpdaterImpl 이 같은 계약을 같은 방식으로 강제한다. 지금
     * 부르는 곳은 billing 의 RechargeReservationService.reserve(REQUIRES_NEW)와 coupon 의 CouponPreparationService.prepareOne
     * (TransactionTemplate)이고 둘 다 쓰기 트랜잭션 안이다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CampaignInfo> findByIdForUpdate(Long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId).map(campaign -> {
            entityManager.refresh(campaign, LockModeType.PESSIMISTIC_WRITE);
            return toInfo(campaign);
        });
    }

    /*
     * newPriorityUntil은 ad_candidate 테이블에만 있고 campaign에는 아직 백업 컬럼이 없다
     * (#156 결정 사항). "신규 우선 노출" 계산 규칙도 문서화된 곳이 없어 지금은 null로 둔다.
     */
    private CampaignInfo toInfo(Campaign campaign) {
        return new CampaignInfo(
                campaign.getId(), campaign.getOwnerId(), campaign.getStoreId(), campaign.getStatus(),
                campaign.getIssueQuantity(), campaign.getUsableStartTime(), campaign.getUsableEndTime(),
                campaign.getStartDate(), campaign.getEndDate(),
                campaign.getDiscountTargetType(), campaign.getDiscountType(), campaign.getDiscountValue(),
                campaign.getTargetMenuId(),
                campaign.getTargetRadius(), campaign.getTargetGender(), campaign.getTargetAgeGroups(),
                campaign.getCreatedAt(), null, campaign.getStatusChangedAt(),
                campaign.getPausedReason(), campaign.getDailyBudget());
    }
}
