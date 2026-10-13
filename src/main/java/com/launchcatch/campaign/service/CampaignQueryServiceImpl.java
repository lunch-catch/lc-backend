package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CampaignQueryServiceImpl implements CampaignQueryService {

    static final int MAX_SERVABLE_CAMPAIGNS = 10000;

    private final CampaignRepository campaignRepository;

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
        List<Campaign> campaigns = campaignRepository.findServableOn(businessDate);
        if (campaigns.size() > MAX_SERVABLE_CAMPAIGNS) {
            throw new IllegalStateException(
                    "findServableOn result must not exceed " + MAX_SERVABLE_CAMPAIGNS + " rows: "
                            + campaigns.size());
        }
        return campaigns.stream().map(this::toInfo).toList();
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
                campaign.getCreatedAt(), null, campaign.getStatusChangedAt());
    }
}
