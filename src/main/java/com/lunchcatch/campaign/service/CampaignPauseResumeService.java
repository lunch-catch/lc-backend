package com.lunchcatch.campaign.service;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.contract.PauseReason;
import com.lunchcatch.campaign.dto.CampaignStatusResponse;
import com.lunchcatch.campaign.entity.ActorType;
import com.lunchcatch.campaign.entity.Campaign;
import com.lunchcatch.campaign.exception.CampaignErrorCode;
import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.repository.CampaignRepository;
import com.lunchcatch.owner.contract.OwnerAccountStatus;
import com.lunchcatch.owner.contract.OwnerStatusQuery;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주의 캠페인 수동 중단·재개(#468)와 직접 종료(#600)다. 본인 소유 확인은 CampaignTargetAudienceService와 같은
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
        validateActiveOwner(ownerId, Action.PAUSE);
        Campaign campaign = findOwnedCampaign(ownerId, campaignId);
        LocalDate businessDate = LocalDate.now(clock);
        try {
            campaignStatusService.pause(campaignId, PauseReason.OWNER, businessDate);
        } catch (IllegalStateException e) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED, e);
        } catch (IllegalArgumentException e) {
            throw storeSummaryMismatch(Action.PAUSE, campaignId, e);
        }
        return toResponse(campaign);
    }

    @Transactional
    public CampaignStatusResponse resume(Long ownerId, Long campaignId) {
        validateActiveOwner(ownerId, Action.RESUME);
        Campaign campaign = findOwnedCampaign(ownerId, campaignId);
        LocalDate businessDate = LocalDate.now(clock);
        try {
            campaignStatusService.resume(campaignId, ActorType.OWNER, businessDate);
        } catch (IllegalStateException e) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED, e);
        } catch (IllegalArgumentException e) {
            throw storeSummaryMismatch(Action.RESUME, campaignId, e);
        }
        return toResponse(campaign);
    }

    /*
     * 점주의 직접 종료(#600)다. 점주는 진행 중(ACTIVE)인 캠페인을 바로 종료할 수 없고, 먼저 일시중지(PAUSED)한
     * 뒤에만 종료할 수 있다(ACTIVE -> PAUSED -> ENDED). 종료는 되돌릴 수 없어서 PAUSED 가 아니면 이미
     * ENDED 인 재요청까지 pause/resume 처럼 멱등하게 받지 않고 CAMPAIGN-006 으로 거부한다.
     * 위에서 PAUSED 를 확인하고 행 잠금을 쥐고 있어 end() 가 IllegalStateException 을 던질 수 없다.
     */
    @Transactional
    public CampaignStatusResponse end(Long ownerId, Long campaignId) {
        validateActiveOwner(ownerId, Action.END);
        Campaign campaign = findOwnedCampaign(ownerId, campaignId);
        if (campaign.getStatus() != CampaignStatus.PAUSED) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }
        LocalDate businessDate = LocalDate.now(clock);
        try {
            campaignStatusService.end(campaignId, ActorType.OWNER, businessDate);
        } catch (IllegalArgumentException e) {
            throw storeSummaryMismatch(Action.END, campaignId, e);
        }
        return toResponse(campaign);
    }

    // 로그의 이벤트명을 리터럴로 쓰려고 동작을 구분한다(LOG-2-03).
    private enum Action {
        PAUSE, RESUME, END
    }

    private void validateActiveOwner(Long ownerId, Action action) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            // 점주 상태가 맞지 않아 거절하는 것은 예상된 클라이언트 쪽 거절이라 INFO 다(LOG-5-02).
            String errorCode = CampaignErrorCode.OWNER_NOT_ACTIVE.getCode();
            switch (action) {
                case PAUSE -> log.info("event=CAMPAIGN_PAUSE_FAILED reason=OWNER_NOT_ACTIVE errorCode={} ownerId={}",
                        errorCode, ownerId);
                case RESUME -> log.info("event=CAMPAIGN_RESUME_FAILED reason=OWNER_NOT_ACTIVE errorCode={} ownerId={}",
                        errorCode, ownerId);
                case END -> log.info("event=CAMPAIGN_END_FAILED reason=OWNER_NOT_ACTIVE errorCode={} ownerId={}",
                        errorCode, ownerId);
            }
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    private Campaign findOwnedCampaign(Long ownerId, Long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    /*
     * StoreCampaignSummaryUpdater.update()는 캠페인의 targetMenuId가 그 가게 소유가 아니면
     * IllegalArgumentException을 던진다. 캠페인 등록 1단계(CampaignCouponConditionService)는 이제
     * 그 소속을 저장 전에 검증하지만(#874), 그 전에 저장된 캠페인이나 직접 저장한 데이터에는 남아 있을 수 있는
     * 서버 쪽 데이터 불일치라 점주의 요청이 잘못된 게 아니다. 그대로 두면
     * GlobalExceptionHandler.handleIllegalArgument가 400 COMMON-002로 잘못 답한다.
     * 어느 ErrorCode도 아닌 RuntimeException으로 다시 던져 handleUnexpected의 500
     * COMMON-001로 보낸다.
     */
    private RuntimeException storeSummaryMismatch(Action action, Long campaignId, IllegalArgumentException e) {
        switch (action) {
            case PAUSE -> log.error("event=CAMPAIGN_PAUSE_FAILED reason=STORE_SUMMARY_MISMATCH campaignId={} detail={}",
                    campaignId, e.getMessage());
            case RESUME -> log.error("event=CAMPAIGN_RESUME_FAILED reason=STORE_SUMMARY_MISMATCH campaignId={} detail={}",
                    campaignId, e.getMessage());
            case END -> log.error("event=CAMPAIGN_END_FAILED reason=STORE_SUMMARY_MISMATCH campaignId={} detail={}",
                    campaignId, e.getMessage());
        }
        return new RuntimeException("store campaign summary update failed: campaignId=" + campaignId, e);
    }

    private CampaignStatusResponse toResponse(Campaign campaign) {
        return new CampaignStatusResponse(campaign.getId(), campaign.getStatus());
    }
}
