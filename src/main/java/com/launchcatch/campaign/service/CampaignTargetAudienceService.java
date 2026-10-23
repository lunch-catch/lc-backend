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
import com.launchcatch.ops.contract.PlatformSettingQueryService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 캠페인 등록 3단계(노출 대상)와 4단계(하루 예산과 집행 기간)를 같은 PATCH로 받는다
 * (campaign-api-spec.md "캠페인 등록(노출 대상, 하루 예산과 집행 기간)"). 보낸 필드만
 * 검사하고 바꾼다(#330).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignTargetAudienceService {

    private static final Set<Integer> ALLOWED_RADIUS = Set.of(500, 1000, 2000, 3000);

    private final CampaignRepository campaignRepository;
    private final OwnerStatusQuery ownerStatusQuery;
    private final PlatformSettingQueryService platformSettingQueryService;
    private final Clock clock;

    @Transactional
    public CampaignUpdateResponse handle(Long ownerId, Long campaignId, CampaignUpdateRequest request) {
        validateActiveOwner(ownerId);

        /*
         * 잠금 없이 조회하면 같은 캠페인에 거의 동시에 들어온 두 PATCH가 각자 메모리에서
         * 고친 뒤 커밋해, 나중에 커밋한 쪽이 먼저 커밋한 쪽의 변경을 조용히 지운다("lost
         * update"). 00:00 스케줄러의 상태 전이(CampaignStatusService)와 경쟁할 때도 이
         * 트랜잭션이 든 스냅샷이 낡아 지나간 상태를 전제로 덮어쓸 수 있다.
         * CampaignStatusService.findOrThrow와 같은 이유로 findByIdForUpdate(비관 잠금)를
         * 쓴다(#253 자체 리뷰로 찾음).
         */
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));

        if (hasAnyTargetAudienceField(request)) {
            applyTargetAudience(campaign, request);
        }
        if (hasAnyBudgetPeriodField(request)) {
            applyBudgetPeriod(campaign, request);
        }
        return toResponse(campaign);
    }

    private void validateActiveOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            log.info("event=CAMPAIGN_TARGET_AUDIENCE_FAILED reason=OWNER_NOT_ACTIVE errorCode={} ownerId={}",
                    CampaignErrorCode.OWNER_NOT_ACTIVE.getCode(), ownerId);
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    private static boolean hasAnyTargetAudienceField(CampaignUpdateRequest request) {
        return request.targetRadius() != null || request.targetGender() != null
                || request.targetAgeGroups() != null;
    }

    private static boolean hasAnyBudgetPeriodField(CampaignUpdateRequest request) {
        return request.dailyBudget() != null || request.startDate() != null || request.endDate() != null;
    }

    // 노출 대상은 DRAFT 상태에서만 바꿀 수 있다(campaign-api-spec.md).
    private void applyTargetAudience(Campaign campaign, CampaignUpdateRequest request) {
        if (request.targetRadius() == null || request.targetGender() == null
                || request.targetAgeGroups() == null) {
            throw new IllegalArgumentException(
                    "targetRadius, targetGender, targetAgeGroups must come together");
        }
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }
        validateTargetRadius(request.targetRadius());
        validateNoDuplicateAgeGroups(request.targetAgeGroups());
        campaign.updateTargetAudience(request.targetRadius(), request.targetGender(), request.targetAgeGroups());
    }

    /*
     * 하루 예산은 DRAFT·ACTIVE 둘 다, 집행 기간은 DRAFT에서만 바꿀 수 있다. ACTIVE에서
     * 집행 기간 필드가 오면 값이 같아도 거부한다(campaign-api-spec.md).
     */
    private void applyBudgetPeriod(Campaign campaign, CampaignUpdateRequest request) {
        if (campaign.getStatus() == CampaignStatus.DRAFT) {
            if (request.dailyBudget() == null || request.startDate() == null || request.endDate() == null) {
                throw new IllegalArgumentException("dailyBudget, startDate, endDate must come together in DRAFT");
            }
            validateDailyBudget(request.dailyBudget());
            validatePeriod(request.startDate(), request.endDate());
            campaign.updateBudgetAndPeriod(request.dailyBudget(), request.startDate(), request.endDate());
        } else if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            if (request.startDate() != null || request.endDate() != null) {
                throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
            }
            validateDailyBudget(request.dailyBudget());
            campaign.reserveDailyBudget(request.dailyBudget());
        } else {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }
    }

    private void validateDailyBudget(Long dailyBudget) {
        long minDailyBudget = platformSettingQueryService.getMinDailyBudget();
        if (dailyBudget < minDailyBudget) {
            throw new CampaignException(CampaignErrorCode.DAILY_BUDGET_TOO_LOW);
        }
    }

    // 집행 기간은 최소 1일 차이가 나야 한다. 같은 날이거나 종료일이 시작일보다 이전이면 거부한다.
    private void validatePeriod(LocalDate startDate, LocalDate endDate) {
        if (!endDate.isAfter(startDate)) {
            throw new CampaignException(CampaignErrorCode.PERIOD_TOO_SHORT);
        }
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        if (startDate.isBefore(tomorrow)) {
            throw new CampaignException(CampaignErrorCode.START_DATE_TOO_EARLY);
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
