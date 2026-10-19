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

/*
 * 점주의 캠페인 활성화 요청(FR-044, #561)이다.
 *
 * 이 메서드 전체를 하나의 트랜잭션으로 묶지 않는다. posterModerationService.moderate()가
 * S3·Rekognition을 부르는데, 그 호출을 트랜잭션 안에서 하면 그 시간만큼 캠페인 행의 잠금과 DB
 * 커넥션을 쥐고 있게 된다(PosterModerationService의 클래스 주석이 이미 트랜잭션을 걸지 않는
 * 이유로 든 것과 같은 문제를, 처음 구현에서 호출하는 쪽이 거꾸로 만들었다. 자체 리뷰로 찾음).
 *
 * 세 구간으로 나눈다.
 * 1) 잠금 없는 findById로 본인 소유·점주 상태·DRAFT 여부를 먼저 거른다. 명백히 잘못된
 *    요청이면 검수를 아예 부르지 않는다.
 * 2) 점주 1명당 요청 빈도를 센 뒤(CampaignActivationRateLimiter) posterModerationService를
 *    트랜잭션 밖에서 부른다.
 * 3) 검수가 PASS면 campaignStatusService.schedule()을 부른다. 이 메서드는 이미 자기
 *    트랜잭션 안에서 findByIdForUpdate로 다시 잠그고 DRAFT 여부를 다시 확인한다.
 *
 * 1번의 확인은 최종 권위가 아니다. 1번을 통과한 두 요청이 거의 동시에 들어오면 둘 다 검수까지
 * 진행할 수 있지만, 3번에서 먼저 도착한 쪽만 실제로 SCHEDULED가 되고 나머지는 그 메서드가
 * 던지는 IllegalStateException을 통해 CAMPAIGN_STATE_NOT_ALLOWED로 거부된다. 같은 캠페인에
 * 대한 중복 전이는 막되, 그 틈에 생기는 중복 검수 호출(낭비)은 감수하는 선택이다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignActivationService {

    private final CampaignRepository campaignRepository;
    private final CampaignStatusService campaignStatusService;
    private final PosterModerationService posterModerationService;
    private final CampaignActivationRateLimiter rateLimiter;
    private final OwnerStatusQuery ownerStatusQuery;

    public CampaignActivateResponse activate(Long ownerId, Long campaignId) {
        validateActiveOwner(ownerId);
        checkDraftOwnedCampaign(ownerId, campaignId);
        rateLimiter.checkLimit(ownerId);

        PosterModerationOutcome outcome = posterModerationService.moderate(campaignId);
        if (outcome.result() != ModerationResult.PASS) {
            return new CampaignActivateResponse(campaignId, CampaignStatus.DRAFT, outcome.reasons());
        }

        try {
            campaignStatusService.schedule(campaignId);
        } catch (IllegalStateException e) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED, e);
        }
        return new CampaignActivateResponse(campaignId, CampaignStatus.SCHEDULED, outcome.reasons());
    }

    private void validateActiveOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            log.warn("event=CAMPAIGN_ACTIVATE_FAILED reason=OWNER_NOT_ACTIVE code={} ownerId={}",
                    CampaignErrorCode.OWNER_NOT_ACTIVE.getCode(), ownerId);
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    /*
     * 잠금 없이 조회한다. 외부 호출(검수) 전에 명백히 잘못된 요청(소유권 없음, DRAFT 아님)을
     * 걸러 검수를 아예 부르지 않으려는 것뿐이고, 그 사이 상태가 바뀔 가능성까지 막지는 않는다.
     * 최종 확인은 campaignStatusService.schedule()의 잠금과 IllegalStateException이 한다.
     */
    private void checkDraftOwnedCampaign(Long ownerId, Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }
    }
}
