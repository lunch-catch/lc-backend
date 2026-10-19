package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.dto.CampaignActivateResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.poster.dto.PosterModerationOutcome;
import com.launchcatch.campaign.poster.entity.ModerationResult;
import com.launchcatch.campaign.poster.service.PosterModerationService;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주의 캠페인 활성화 요청(FR-044, #561)이다. 본인 소유 확인은 CampaignPauseResumeService와
 * 같은 패턴(findByIdForUpdate + ownerId 필터)을 쓴다.
 *
 * DRAFT 여부는 CampaignStatusService.schedule()의 IllegalStateException에 맡기지 않고
 * 여기서 먼저 확인한다. 검수가 FAIL이면 schedule()을 부르지 않는데, 그 경로에서도 DRAFT가
 * 아닌 상태의 요청은 거부해야 해서 전이 쪽 예외만으로는 모든 경로를 덮지 못한다.
 * findByIdForUpdate가 쥔 잠금이 이 트랜잭션이 끝날 때까지 유지되어, 이 확인과 schedule() 사이에
 * 상태가 바뀔 수 없다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignActivationService {

    private final CampaignRepository campaignRepository;
    private final CampaignStatusService campaignStatusService;
    private final PosterModerationService posterModerationService;
    private final OwnerStatusQuery ownerStatusQuery;

    @Transactional
    public CampaignActivateResponse activate(Long ownerId, Long campaignId) {
        validateActiveOwner(ownerId);
        Campaign campaign = findOwnedCampaign(ownerId, campaignId);
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }

        PosterModerationOutcome outcome = posterModerationService.moderate(campaignId);
        if (outcome.result() == ModerationResult.PASS) {
            campaignStatusService.schedule(campaignId);
        }
        return new CampaignActivateResponse(campaign.getId(), campaign.getStatus(), outcome.reasons());
    }

    private void validateActiveOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            log.warn("event=CAMPAIGN_ACTIVATE_FAILED reason=OWNER_NOT_ACTIVE code={} ownerId={}",
                    CampaignErrorCode.OWNER_NOT_ACTIVE.getCode(), ownerId);
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    private Campaign findOwnedCampaign(Long ownerId, Long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }
}
