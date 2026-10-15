package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.dto.CampaignUpdateRequest;
import com.launchcatch.campaign.dto.CampaignUpdateResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 캠페인 등록 3단계(노출 대상). PATCH /v1/owner/campaigns/{campaignId}는 4단계(하루
 * 예산과 집행 기간)와 엔드포인트를 공유하지만(campaign-api-spec.md), 그 기능은 아직
 * 구현 이슈가 없어 dailyBudget/startDate/endDate가 오면 거부한다(#253, 임시 결정).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignTargetAudienceService {

    private static final Set<Integer> ALLOWED_RADIUS = Set.of(500, 1000, 2000, 3000);

    private final CampaignRepository campaignRepository;
    private final OwnerStatusQuery ownerStatusQuery;

    @Transactional
    public CampaignUpdateResponse handle(Long ownerId, Long campaignId, CampaignUpdateRequest request) {
        validateActiveOwner(ownerId);
        validateNoBudgetPeriodFields(request);
        validateTargetRadius(request.targetRadius());
        validateNoDuplicateAgeGroups(request.targetAgeGroups());

        Campaign campaign = campaignRepository.findById(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }
        campaign.updateTargetAudience(request.targetRadius(), request.targetGender(), request.targetAgeGroups());
        return toResponse(campaign);
    }

    private void validateActiveOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            log.warn("event=CAMPAIGN_TARGET_AUDIENCE_FAILED reason=OWNER_NOT_ACTIVE code={} ownerId={}",
                    CampaignErrorCode.OWNER_NOT_ACTIVE.getCode(), ownerId);
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    // 하루 예산·집행 기간(4단계)은 아직 이 PR의 범위가 아니다(#253 작업 절 참고).
    private void validateNoBudgetPeriodFields(CampaignUpdateRequest request) {
        if (request.dailyBudget() != null || request.startDate() != null || request.endDate() != null) {
            throw new IllegalArgumentException(
                    "dailyBudget/startDate/endDate are not supported by this endpoint yet");
        }
    }

    private void validateTargetRadius(int targetRadius) {
        if (!ALLOWED_RADIUS.contains(targetRadius)) {
            throw new IllegalArgumentException("targetRadius must be one of " + ALLOWED_RADIUS + ": " + targetRadius);
        }
    }

    private void validateNoDuplicateAgeGroups(List<AgeGroup> targetAgeGroups) {
        if (new HashSet<>(targetAgeGroups).size() != targetAgeGroups.size()) {
            throw new IllegalArgumentException("targetAgeGroups must not contain duplicates: " + targetAgeGroups);
        }
    }

    private CampaignUpdateResponse toResponse(Campaign campaign) {
        return new CampaignUpdateResponse(
                campaign.getId(), campaign.getTargetRadius(), campaign.getTargetGender(),
                campaign.getTargetAgeGroups(), campaign.getDailyBudget(), campaign.getStartDate(),
                campaign.getEndDate());
    }
}
