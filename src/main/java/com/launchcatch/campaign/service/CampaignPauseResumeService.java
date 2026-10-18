package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.dto.CampaignStatusResponse;
import com.launchcatch.campaign.entity.ActorType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주의 캠페인 수동 중단·재개(#468)다. 본인 소유 확인은 CampaignTargetAudienceService와 같은
 * 패턴(findByIdForUpdate + ownerId 필터)을 쓰고, 실제 상태 전이는 CampaignStatusService.pause()/
 * resume()에 맡긴다. 이 메서드가 @Transactional이라 CampaignStatusService의 findByIdForUpdate가
 * 같은 트랜잭션에 참여해, 아래 조회가 쥔 행 잠금이 그 호출이 끝날 때까지 유지된다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignPauseResumeService {

    private final CampaignRepository campaignRepository;
    private final CampaignStatusService campaignStatusService;
    private final OwnerStatusQuery ownerStatusQuery;
    private final Clock clock;

    @Transactional
    public CampaignStatusResponse pause(Long ownerId, Long campaignId) {
        validateActiveOwner(ownerId, "CAMPAIGN_PAUSE_FAILED");
        Campaign campaign = findOwnedCampaign(ownerId, campaignId);
        LocalDate businessDate = LocalDate.now(clock);
        try {
            campaignStatusService.pause(campaignId, PauseReason.OWNER, businessDate);
        } catch (IllegalStateException e) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED, e);
        }
        return toResponse(campaign);
    }

    @Transactional
    public CampaignStatusResponse resume(Long ownerId, Long campaignId) {
        validateActiveOwner(ownerId, "CAMPAIGN_RESUME_FAILED");
        Campaign campaign = findOwnedCampaign(ownerId, campaignId);
        LocalDate businessDate = LocalDate.now(clock);
        try {
            campaignStatusService.resume(campaignId, ActorType.OWNER, businessDate);
        } catch (IllegalStateException e) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED, e);
        }
        return toResponse(campaign);
    }

    private void validateActiveOwner(Long ownerId, String event) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            log.warn("event={} reason=OWNER_NOT_ACTIVE code={} ownerId={}",
                    event, CampaignErrorCode.OWNER_NOT_ACTIVE.getCode(), ownerId);
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    private Campaign findOwnedCampaign(Long ownerId, Long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    private CampaignStatusResponse toResponse(Campaign campaign) {
        return new CampaignStatusResponse(campaign.getId(), campaign.getStatus());
    }
}
