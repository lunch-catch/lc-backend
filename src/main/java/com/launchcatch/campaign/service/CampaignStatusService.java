package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignEndedEvent;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.entity.ActorType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.entity.CampaignStatusLog;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import com.launchcatch.store.contract.StoreCampaignStatus;
import com.launchcatch.store.contract.StoreCampaignSummaryCommand;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * FR-092 상태 전이를 담당한다. schedule, activate는 00:00 배치 등 SYSTEM 전용이라 상태
 * 변경 이벤트를 발행하지 않는다(00:00 배치가 같은 흐름에서 바로 다시 조회하기 때문). pause,
 * resume, end만 낮에 일어나는 전이라 CampaignStatusChangedEvent를 발행한다.
 *
 * 다섯 메서드 모두 이미 목표 상태면 예외 대신 조용히 성공한다(멱등한 재시도). 00:00 묶음의
 * 이어받기와 자동 재실행, billing의 재시도가 모두 같은 단계를 두 번 실행할 수 있다고 전제하기
 * 때문이다(런치캐치_배치_운영.md). 목표 상태가 아닌데 전이가 안 되는 경우는 엔티티 쪽
 * IllegalStateException이 그대로 막는다.
 *
 * store.contract.StoreCampaignSummaryUpdater 호출(설계 문서 2.4, campaign -> store 간선)은
 * pause, resume에서만 건다(#468). 같은 트랜잭션 안에서 상태 변경과 로그 저장 다음에 부른다.
 * schedule, activate, end, pauseFromFunding, resumeFromFunding은 아직 호출하지 않는다. 멱등한
 * 재시도로 조용히 반환하는 자리(이미 목표 상태)에서는 아무것도 바뀌지 않으므로 이 호출도 하지
 * 않는다.
 *
 * pauseFromFunding, resumeFromFunding은 정산의 FundingJudgement 전용 전이다(#198). pause,
 * resume과 다른 점은 "지금 상태가 그 판정을 받을 자리가 아니면 예외 대신 무시한다" 는 것이다.
 * NO_POINTS 판정이 이미 PAUSED(ADMIN/OWNER)인 캠페인에 오면 그 중단을 덮어쓰면 안 되고,
 * RESUME 판정이 PAUSED(ADMIN/OWNER)나 ENDED에 오면 정산이 모르는 이유로 멈춘 캠페인을
 * 되돌리면 안 된다.
 */
@Service
@RequiredArgsConstructor
public class CampaignStatusService {

    private final CampaignRepository campaignRepository;
    private final CampaignStatusLogRepository campaignStatusLogRepository;
    private final StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public void schedule(Long campaignId) {
        schedule(campaignId, () -> { });
    }

    /*
     * precondition은 캠페인 잠금을 잡은 뒤, 상태를 바꾸기 전에 실행한다. 호출하는 쪽이 이
     * 잠금 밑에서 함께 확인하고 싶은 것(예: 캠페인 활성화의 포스터 변경 여부, #561 코드래빗
     * 리뷰로 찾음)을 넣는다. 이 서비스는 그 확인이 무엇인지 모른다. precondition이 예외를
     * 던지면 상태를 바꾸지 않고 트랜잭션이 롤백된다.
     */
    @Transactional
    public void schedule(Long campaignId, Runnable precondition) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.SCHEDULED) {
            return;
        }
        precondition.run();
        CampaignStatus from = campaign.getStatus();
        campaign.schedule(LocalDateTime.now(clock));
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, null);
    }

    @Transactional
    public void activate(Long campaignId) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            return;
        }
        CampaignStatus from = campaign.getStatus();
        campaign.activate(LocalDateTime.now(clock));
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, null);
    }

    @Transactional
    public void pause(Long campaignId, PauseReason reason, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.PAUSED && campaign.getPausedReason() == reason) {
            return;
        }
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.pause(reason, now);
        log(campaignId, from, campaign.getStatus(), actorTypeOf(reason), null);
        updateStoreSummary(campaign, businessDate);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, false, now));
    }

    @Transactional
    public void resume(Long campaignId, ActorType actorType, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            return;
        }
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.resume(now);
        log(campaignId, from, campaign.getStatus(), actorType, null);
        updateStoreSummary(campaign, businessDate);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, true, now));
    }

    @Transactional
    public void end(Long campaignId, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.ENDED) {
            return;
        }
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.end(now);
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, null);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, false, now));
        events.publishEvent(new CampaignEndedEvent(campaignId, now));
    }

    @Transactional
    public void pauseFromFunding(Long campaignId, LocalDate businessDate, String judgementNote) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.PAUSED && campaign.getPausedReason() == PauseReason.NO_POINTS) {
            return;
        }
        if (campaign.getStatus() != CampaignStatus.ACTIVE) {
            return;
        }
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.pause(PauseReason.NO_POINTS, now);
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, judgementNote);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, false, now));
    }

    @Transactional
    public void resumeFromFunding(Long campaignId, LocalDate businessDate, String judgementNote) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            return;
        }
        if (campaign.getStatus() != CampaignStatus.PAUSED || campaign.getPausedReason() != PauseReason.NO_POINTS) {
            return;
        }
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.resume(now);
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, judgementNote);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, true, now));
    }

    private Campaign findOrThrow(Long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("campaign not found: " + campaignId));
    }

    private void log(
            Long campaignId, CampaignStatus from, CampaignStatus to, ActorType actorType, String reason) {
        campaignStatusLogRepository.save(CampaignStatusLog.of(campaignId, from, to, actorType, reason));
    }

    /*
     * paused_reason과 actor_type은 다른 컬럼이다. NO_POINTS는 정산이 판정한 결과라
     * 사람이 아니라 SYSTEM이 행위자다.
     */
    private ActorType actorTypeOf(PauseReason reason) {
        return switch (reason) {
            case ADMIN -> ActorType.ADMIN;
            case OWNER -> ActorType.OWNER;
            case NO_POINTS -> ActorType.SYSTEM;
        };
    }

    private void updateStoreSummary(Campaign campaign, LocalDate businessDate) {
        StoreCampaignSummaryCommand command = new StoreCampaignSummaryCommand(
                businessDate,
                toStoreCampaignStatus(campaign.getStatus()),
                toStoreDiscountTargetType(campaign.getDiscountTargetType()),
                toStoreDiscountType(campaign.getDiscountType()),
                campaign.getDiscountValue(),
                campaign.getTargetMenuId(),
                campaign.getUsableStartTime(),
                campaign.getUsableEndTime());
        storeCampaignSummaryUpdater.update(campaign.getStoreId(), campaign.getId(), command);
    }

    private static StoreCampaignStatus toStoreCampaignStatus(CampaignStatus status) {
        return switch (status) {
            case DRAFT -> StoreCampaignStatus.DRAFT;
            case SCHEDULED -> StoreCampaignStatus.SCHEDULED;
            case ACTIVE -> StoreCampaignStatus.ACTIVE;
            case PAUSED -> StoreCampaignStatus.PAUSED;
            case ENDED -> StoreCampaignStatus.ENDED;
        };
    }

    private static StoreDiscountTargetType toStoreDiscountTargetType(DiscountTargetType discountTargetType) {
        return switch (discountTargetType) {
            case ALL -> StoreDiscountTargetType.ALL;
            case MENU -> StoreDiscountTargetType.MENU;
        };
    }

    private static StoreDiscountType toStoreDiscountType(DiscountType discountType) {
        return switch (discountType) {
            case PERCENT -> StoreDiscountType.PERCENT;
            case AMOUNT -> StoreDiscountType.AMOUNT;
        };
    }
}
