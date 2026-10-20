package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.dto.CampaignActivateResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.poster.dto.PosterModerationOutcome;
import com.launchcatch.campaign.poster.entity.ModerationResult;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.poster.service.PosterModerationService;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.time.LocalDateTime;
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
 *
 * 검수(2번)가 트랜잭션 밖에 있어서, 검수가 끝나고 schedule()이 캠페인 잠금을 잡기 전까지
 * 포스터가 바뀔 수 있다(점주가 새 포스터를 올리는 요청은 PosterUploadService가 같은 캠페인
 * 잠금을 거쳐 덮어쓴다). 그러면 검수 결과는 더 이상 지금 포스터의 것이 아니다. 그래서
 * schedule()에 precondition을 넘겨, 캠페인 잠금을 잡은 뒤(포스터 덮어쓰기도 같은 잠금을
 * 거치므로 그 사이엔 포스터가 못 바뀐다) 검수 시점의 포스터 updatedAt과 지금 값이 같은지
 * 다시 확인한다. 바뀌었으면 전이하지 않는다(코드래빗 리뷰로 찾음, #561).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CampaignActivationService {

    private final CampaignRepository campaignRepository;
    private final CampaignStatusService campaignStatusService;
    private final PosterModerationService posterModerationService;
    private final PosterRepository posterRepository;
    private final CampaignActivationRateLimiter rateLimiter;
    private final OwnerStatusQuery ownerStatusQuery;

    public CampaignActivateResponse activate(Long ownerId, Long campaignId) {
        validateActiveOwner(ownerId);
        checkDraftOwnedCampaign(ownerId, campaignId);
        rateLimiter.checkLimit(ownerId);

        LocalDateTime posterUpdatedAtAtModeration = posterRepository.findByCampaignId(campaignId)
                .map(Poster::getUpdatedAt)
                .orElse(null);
        PosterModerationOutcome outcome = posterModerationService.moderate(campaignId);
        if (outcome.result() != ModerationResult.PASS) {
            return new CampaignActivateResponse(campaignId, CampaignStatus.DRAFT, outcome.reasons());
        }

        try {
            campaignStatusService.schedule(campaignId,
                    () -> verifyPosterUnchanged(campaignId, posterUpdatedAtAtModeration));
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

    /*
     * campaignStatusService.schedule()이 캠페인 잠금을 잡은 뒤 호출한다(그 메서드의
     * precondition). 포스터 덮어쓰기(PosterUploadService)도 활성화 전에 같은 잠금을 거치므로,
     * 이 확인과 schedule()의 실제 전이 사이에는 포스터가 바뀔 수 없다.
     */
    private void verifyPosterUnchanged(Long campaignId, LocalDateTime posterUpdatedAtAtModeration) {
        Poster poster = posterRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.POSTER_NOT_SAVED));
        if (!poster.getUpdatedAt().equals(posterUpdatedAtAtModeration)) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }
    }
}
