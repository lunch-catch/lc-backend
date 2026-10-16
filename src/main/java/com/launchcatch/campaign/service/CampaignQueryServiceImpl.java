package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

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

    @Override
    public Optional<CampaignInfo> findByIdForUpdate(Long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId).map(this::toInfo);
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
