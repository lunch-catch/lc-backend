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
import com.launchcatch.campaign.repository.CampaignSummaryRow;
import com.launchcatch.store.contract.StoreCampaignStatus;
import com.launchcatch.store.contract.StoreCampaignSummaryCommand;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * FR-092 상태 전이를 담당한다. schedule, activate는 00:00 배치 등 SYSTEM 전용이라 상태
 * 변경 이벤트를 발행하지 않는다(00:00 배치가 같은 흐름에서 바로 다시 조회하기 때문). pause,
 * resume, end만 낮에 일어나는 전이라 CampaignStatusChangedEvent를 발행한다.
 *
 * 여섯 메서드(cancel 포함, #597) 모두 이미 목표 상태면 예외 대신 조용히 성공한다(멱등한 재시도). 00:00 묶음의
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
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignStatusService {

    private static final String EXPIRED_BEFORE_START_REASON = "집행 시작 전에 집행 기간이 지남";

    private final CampaignRepository campaignRepository;
    private final CampaignStatusLogRepository campaignStatusLogRepository;
    private final StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public void schedule(Long campaignId) {
        schedule(campaignId, () -> { }, true);
    }

    /*
     * precondition은 캠페인 잠금을 잡은 뒤, 상태를 바꾸기 전에 실행한다. 호출하는 쪽이 이
     * 잠금 밑에서 함께 확인하고 싶은 것(예: 캠페인 활성화의 포스터 변경 여부, #561 코드래빗
     * 리뷰로 찾음)을 넣는다. 이 서비스는 그 확인이 무엇인지 모른다. precondition이 예외를
     * 던지면 상태를 바꾸지 않고 트랜잭션이 롤백된다.
     *
     * 이미 SCHEDULED여도 멱등하게 조용히 성공하지 않고 IllegalStateException을 던진다(#576
     * 코드래빗 리뷰로 찾음). 멱등하게 반환하면 그 precondition을 건너뛰어서, 사전 확인 뒤
     * 다른 요청이 먼저 전이를 끝낸 경우 이 요청은 자신의 확인을 거치지 않은 채 성공
     * 응답(SCHEDULED)을 내보내게 된다. CampaignActivationService가 이 예외를
     * CAMPAIGN_STATE_NOT_ALLOWED로 바꿔 거부해야, 그 요청이 실제로 전이를 일으켰는지와
     * 응답이 항상 맞는다.
     */
    @Transactional
    public void schedule(Long campaignId, Runnable precondition) {
        schedule(campaignId, precondition, false);
    }

    private void schedule(Long campaignId, Runnable precondition, boolean idempotent) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.SCHEDULED) {
            if (idempotent) {
                return;
            }
            throw new IllegalStateException("campaign is already SCHEDULED: " + campaignId);
        }
        precondition.run();
        CampaignStatus from = campaign.getStatus();
        campaign.schedule(LocalDateTime.now(clock));
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, null);
    }

    @Transactional
    public void activate(Long campaignId) {
        activateCampaign(campaignId);
    }

    /*
     * 00:00 배치(#569)가 집행 시작일이 된 캠페인을 켤 때 쓴다. 상태 변경과 로그에 더해 가게 요약을 ACTIVE 로 갱신한다.
     * 이미 ACTIVE 면 아무것도 하지 않아 같은 영업일에 두 번 불려도 결과가 같다(멱등).
     *
     * 배치가 시작 대상 목록을 읽은 뒤 잠금 없이 반복하므로, 그 사이 점주의 취소(#597)가 먼저 잠금을 잡으면 SCHEDULED 가
     * 아닌 캠페인을 만난다. 행 잠금을 잡은 뒤 상태가 SCHEDULED 도 ACTIVE 도 아니면 예외 없이 건너뛴다. 예외를 던지면 시작
     * 단계가 실패해 정산이 멈춘다. 한 건 호출 activate(Long)은 이렇게 건너뛰지 않고 SCHEDULED 가 아니면 거부한다.
     */
    @Transactional
    public void activate(Long campaignId, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        CampaignStatus status = campaign.getStatus();
        if (status == CampaignStatus.ACTIVE) {
            return;
        }
        if (status != CampaignStatus.SCHEDULED) {
            log.info("event=CAMPAIGN_DAILY_START_SKIPPED reason=NOT_SCHEDULED campaignId={} status={} businessDate={}",
                    campaignId, status, businessDate);
            return;
        }
        transitionToActive(campaignId, campaign);
        updateStoreSummary(campaign, businessDate);
    }

    // 이미 ACTIVE 라 바꿀 것이 없으면 null 을 돌려준다.
    private Campaign activateCampaign(Long campaignId) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            return null;
        }
        transitionToActive(campaignId, campaign);
        return campaign;
    }

    private void transitionToActive(Long campaignId, Campaign campaign) {
        CampaignStatus from = campaign.getStatus();
        campaign.activate(LocalDateTime.now(clock));
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, null);
    }

    /*
     * 점주의 취소다(#597). schedule 처럼 이벤트를 내지 않고 가게 요약도 갱신하지 않는다.
     * 다른 도메인은 SCHEDULED 를 그때그때 읽기만 해서 되돌릴 것이 없다.
     *
     * 00:00 배치의 activate 와 같은 행 잠금으로 줄을 선다. 취소가 먼저면 activate 가 DRAFT 를 보고
     * IllegalStateException 을 던진다.
     */
    @Transactional
    public void cancel(Long campaignId) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.DRAFT) {
            return;
        }
        CampaignStatus from = campaign.getStatus();
        campaign.cancel(LocalDateTime.now(clock));
        log(campaignId, from, campaign.getStatus(), ActorType.OWNER, null);
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
        end(campaignId, ActorType.SYSTEM, businessDate);
    }

    // 점주가 직접 종료할 때(#600)는 행위자를 OWNER 로 남긴다. 집행 기간이 끝난 정기 종료는 SYSTEM 이다.
    @Transactional
    public void end(Long campaignId, ActorType actorType, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        if (campaign.getStatus() == CampaignStatus.ENDED) {
            return;
        }
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.end(now);
        log(campaignId, from, campaign.getStatus(), actorType, null);
        updateStoreSummary(campaign, businessDate);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, false, now));
        events.publishEvent(new CampaignEndedEvent(campaignId, now));
    }

    /*
     * 00:00 배치(#902)가 집행이 시작되기 전에 기간이 지난 SCHEDULED 를 ENDED 로 바꾼다. cancel 과 같이 이벤트를 내지 않고
     * 가게 요약도 갱신하지 않는다. 다른 도메인은 SCHEDULED 를 그때그때 읽기만 했고 ACTIVE 가 된 적이 없어 되돌릴 것이 없다.
     *
     * 목록을 읽은 뒤 행 잠금을 잡기 전에 점주가 취소해 DRAFT 가 되거나, 취소 뒤 기간을 고쳐 다시 예약했을 수 있다. 잠금을
     * 잡은 뒤 SCHEDULED 이고 종료일이 영업일보다 앞인지 다시 확인하고, 아니면 예외 없이 건너뛴다. 이미 ENDED 면 아무것도
     * 하지 않아 같은 영업일에 다시 불려도 결과가 같다(멱등).
     */
    @Transactional
    public void endExpiredScheduled(Long campaignId, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        CampaignStatus status = campaign.getStatus();
        if (status == CampaignStatus.ENDED) {
            return;
        }
        if (status != CampaignStatus.SCHEDULED
                || campaign.getEndDate() == null || !campaign.getEndDate().isBefore(businessDate)) {
            log.info("event=CAMPAIGN_DAILY_END_SKIPPED reason=NOT_EXPIRED_SCHEDULED campaignId={} status={} endDate={} businessDate={}",
                    campaignId, status, campaign.getEndDate(), businessDate);
            return;
        }
        campaign.endBeforeStart(LocalDateTime.now(clock));
        log(campaignId, status, campaign.getStatus(), ActorType.SYSTEM, EXPIRED_BEFORE_START_REASON);
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

    /*
     * 00:00 배치(#840)가 예약된 하루 예산 변경을 적용한다. 행 잠금을 잡은 뒤 상태를 다시 확인해, 목록을 읽은 뒤 PAUSED 나
     * ENDED 가 된 캠페인에는 적용하지 않는다. 예약 값이 없으면 아무것도 하지 않아 같은 영업일에 다시 불러도 결과가 같다.
     */
    @Transactional
    public boolean applyPendingDailyBudget(Long campaignId) {
        return findOrThrow(campaignId).applyPendingDailyBudget();
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
        storeCampaignSummaryUpdater.update(campaign.getStoreId(), campaign.getId(), toSummaryCommand(campaign, businessDate));
    }

    // 00:00 배치의 일일 요약 준비(#838)도 같은 값을 만들어 쓰도록 패키지 안에 연다.
    static StoreCampaignSummaryCommand toSummaryCommand(Campaign campaign, LocalDate businessDate) {
        return toSummaryCommand(CampaignSummaryRow.from(campaign), businessDate);
    }

    static StoreCampaignSummaryCommand toSummaryCommand(CampaignSummaryRow row, LocalDate businessDate) {
        return new StoreCampaignSummaryCommand(
                businessDate,
                toStoreCampaignStatus(row.status()),
                toStoreDiscountTargetType(row.discountTargetType()),
                toStoreDiscountType(row.discountType()),
                row.discountValue(),
                row.targetMenuId(),
                row.usableStartTime(),
                row.usableEndTime());
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
