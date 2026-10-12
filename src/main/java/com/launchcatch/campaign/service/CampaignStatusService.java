package com.launchcatch.campaign.service;

import com.launchcatch.campaign.contract.CampaignEndedEvent;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import com.launchcatch.campaign.entity.ActorType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.entity.CampaignStatusLog;
import com.launchcatch.campaign.entity.PauseReason;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 95행 상태 전이를 담당한다. schedule, activate는 00:00 배치 등 SYSTEM 전용이라 상태
 * 변경 이벤트를 발행하지 않는다(00:00 배치가 같은 흐름에서 바로 다시 조회하기 때문). pause,
 * resume, end만 낮에 일어나는 전이라 CampaignStatusChangedEvent를 발행한다.
 *
 * store.contract.StoreCampaignSummaryUpdater 호출(설계 문서 2.4, campaign -> store 간선)은
 * store 쪽에 그 인터페이스가 아직 없어 이번 이슈에서 뺐다. 생기면 이어 붙인다.
 */
@Service
@RequiredArgsConstructor
public class CampaignStatusService {

    private final CampaignRepository campaignRepository;
    private final CampaignStatusLogRepository campaignStatusLogRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public void schedule(Long campaignId) {
        Campaign campaign = findOrThrow(campaignId);
        CampaignStatus from = campaign.getStatus();
        campaign.schedule(LocalDateTime.now(clock));
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, null);
    }

    @Transactional
    public void activate(Long campaignId) {
        Campaign campaign = findOrThrow(campaignId);
        CampaignStatus from = campaign.getStatus();
        campaign.activate(LocalDateTime.now(clock));
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, null);
    }

    @Transactional
    public void pause(Long campaignId, PauseReason reason, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.pause(reason, now);
        log(campaignId, from, campaign.getStatus(), actorTypeOf(reason), null);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, false, now));
    }

    @Transactional
    public void resume(Long campaignId, ActorType actorType, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.resume(now);
        log(campaignId, from, campaign.getStatus(), actorType, null);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, true, now));
    }

    @Transactional
    public void end(Long campaignId, LocalDate businessDate) {
        Campaign campaign = findOrThrow(campaignId);
        CampaignStatus from = campaign.getStatus();
        LocalDateTime now = LocalDateTime.now(clock);
        campaign.end(now);
        log(campaignId, from, campaign.getStatus(), ActorType.SYSTEM, null);
        events.publishEvent(new CampaignStatusChangedEvent(campaignId, businessDate, false, now));
        events.publishEvent(new CampaignEndedEvent(campaignId, now));
    }

    private Campaign findOrThrow(Long campaignId) {
        return campaignRepository.findById(campaignId)
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
}
