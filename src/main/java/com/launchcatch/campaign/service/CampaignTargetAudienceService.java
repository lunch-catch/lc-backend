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

        /*
         * 잠금 없이 조회하면 같은 캠페인에 거의 동시에 들어온 두 PATCH가 각자 메모리에서
         * 고친 뒤 커밋해, 나중에 커밋한 쪽이 먼저 커밋한 쪽의 변경을 조용히 지운다("lost
         * update"). 00:00 스케줄러의 상태 전이(CampaignStatusService)와 경쟁할 때도 이
         * 트랜잭션이 든 스냅샷이 낡아 DRAFT가 아닌 캠페인을 DRAFT로 착각해 덮어쓸 수 있다.
         * CampaignStatusService.findOrThrow와 같은 이유로 findByIdForUpdate(비관 잠금)를
         * 쓴다(#253 자체 리뷰로 찾음).
         */
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
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
