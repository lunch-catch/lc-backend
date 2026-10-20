package com.launchcatch.campaign.service;

import com.launchcatch.campaign.dto.CampaignStatusResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주의 SCHEDULED 캠페인 취소(#597)다. 점주 상태와 본인 소유 확인은 CampaignPauseResumeService 와
 * 같고, 실제 전이는 CampaignStatusService.cancel()에 맡긴다. 이 메서드가 @Transactional 이라 아래
 * 조회가 쥔 행 잠금이 그 호출이 끝날 때까지 유지된다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignCancelService {

    private final CampaignRepository campaignRepository;
    private final CampaignStatusService campaignStatusService;
    private final OwnerStatusQuery ownerStatusQuery;

    @Transactional
    public CampaignStatusResponse cancel(Long ownerId, Long campaignId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            log.warn("event=CAMPAIGN_CANCEL_FAILED reason=OWNER_NOT_ACTIVE code={} ownerId={}",
                    CampaignErrorCode.OWNER_NOT_ACTIVE.getCode(), ownerId);
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        try {
            campaignStatusService.cancel(campaignId);
        } catch (IllegalStateException e) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED, e);
        }
        return new CampaignStatusResponse(campaign.getId(), campaign.getStatus());
    }
}
