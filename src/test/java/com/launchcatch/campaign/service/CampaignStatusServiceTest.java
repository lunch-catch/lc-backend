package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignEndedEvent;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.ActorType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.entity.CampaignStatusLog;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class CampaignStatusServiceTest {

    private static final Long CAMPAIGN_ID = 1L;
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignStatusLogRepository campaignStatusLogRepository;
    @Mock
    private ApplicationEventPublisher events;

    private CampaignStatusService service;

    @BeforeEach
    void setUp() {
        service = new CampaignStatusService(campaignRepository, campaignStatusLogRepository, events, FIXED_CLOCK);
    }

    @Test
    @DisplayName("DRAFT 캠페인을 schedule 하면 SCHEDULED 로 바뀌고 SYSTEM 행위자로 로그가 남는다")
    void DRAFT를_schedule하면_SCHEDULED로_바뀐다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.schedule(CAMPAIGN_ID);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        CampaignStatusLog log = captureLog();
        assertThat(log.getFromStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(log.getToStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(log.getActorType()).isEqualTo(ActorType.SYSTEM);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 SCHEDULED면 다시 schedule해도 로그 없이 조용히 성공한다(멱등한 재시도)")
    void 이미_SCHEDULED면_다시_schedule해도_조용히_성공한다() {
        Campaign campaign = scheduledCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.schedule(CAMPAIGN_ID);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("DRAFT도 SCHEDULED도 아니면 schedule을 거부한다")
    void DRAFT도_SCHEDULED도_아니면_schedule을_거부한다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.schedule(CAMPAIGN_ID)).isInstanceOf(IllegalStateException.class);
        verify(campaignStatusLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("SCHEDULED 캠페인을 activate 하면 ACTIVE 로 바뀌고 SYSTEM 행위자로 로그가 남는다")
    void SCHEDULED를_activate하면_ACTIVE로_바뀐다() {
        Campaign campaign = scheduledCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.activate(CAMPAIGN_ID);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        CampaignStatusLog log = captureLog();
        assertThat(log.getFromStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(log.getToStatus()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(log.getActorType()).isEqualTo(ActorType.SYSTEM);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("SCHEDULED가 아니면 activate를 거부한다")
    void SCHEDULED가_아니면_activate를_거부한다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.activate(CAMPAIGN_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 ACTIVE면 다시 activate해도 로그 없이 조용히 성공한다(멱등한 재시도)")
    void 이미_ACTIVE면_다시_activate해도_조용히_성공한다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.activate(CAMPAIGN_ID);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        verify(campaignStatusLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("ACTIVE 캠페인을 ADMIN 이유로 pause 하면 PAUSED 로 바뀌고 ADMIN 행위자로 기록되며 상태 변경 이벤트를 발행한다")
    void ACTIVE를_ADMIN이유로_pause하면_PAUSED로_바뀐다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pause(CAMPAIGN_ID, PauseReason.ADMIN, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        CampaignStatusLog log = captureLog();
        assertThat(log.getActorType()).isEqualTo(ActorType.ADMIN);
        CampaignStatusChangedEvent event = captureStatusChangedEvent();
        assertThat(event.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(event.businessDate()).isEqualTo(BUSINESS_DATE);
        assertThat(event.servable()).isFalse();
    }

    @Test
    @DisplayName("NO_POINTS 이유의 pause는 SYSTEM 행위자로 기록한다")
    void NO_POINTS이유의_pause는_SYSTEM으로_기록한다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pause(CAMPAIGN_ID, PauseReason.NO_POINTS, BUSINESS_DATE);

        assertThat(captureLog().getActorType()).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    @DisplayName("OWNER 이유의 pause는 OWNER 행위자로 기록한다")
    void OWNER이유의_pause는_OWNER로_기록한다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pause(CAMPAIGN_ID, PauseReason.OWNER, BUSINESS_DATE);

        assertThat(captureLog().getActorType()).isEqualTo(ActorType.OWNER);
    }

    @Test
    @DisplayName("ACTIVE가 아니면 pause를 거부한다")
    void ACTIVE가_아니면_pause를_거부한다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.pause(CAMPAIGN_ID, PauseReason.ADMIN, BUSINESS_DATE))
                .isInstanceOf(IllegalStateException.class);
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이미 같은 이유로 PAUSED면 다시 pause해도 로그 없이 조용히 성공한다(멱등한 재시도)")
    void 같은_이유로_다시_pause하면_조용히_성공한다() {
        Campaign campaign = pausedCampaign(PauseReason.OWNER);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pause(CAMPAIGN_ID, PauseReason.OWNER, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("다른 이유로 이미 PAUSED면 pause를 다시 요청해도 거부한다(진짜 충돌)")
    void 다른_이유로_PAUSED면_pause를_다시_요청해도_거부한다() {
        Campaign campaign = pausedCampaign(PauseReason.ADMIN);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.pause(CAMPAIGN_ID, PauseReason.OWNER, BUSINESS_DATE))
                .isInstanceOf(IllegalStateException.class);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("PAUSED 캠페인을 resume 하면 ACTIVE 로 바뀌고 넘긴 행위자로 기록되며 서빙 가능 이벤트를 발행한다")
    void PAUSED를_resume하면_ACTIVE로_바뀐다() {
        Campaign campaign = pausedCampaign(PauseReason.OWNER);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.resume(CAMPAIGN_ID, ActorType.OWNER, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        CampaignStatusLog log = captureLog();
        assertThat(log.getActorType()).isEqualTo(ActorType.OWNER);
        CampaignStatusChangedEvent event = captureStatusChangedEvent();
        assertThat(event.servable()).isTrue();
    }

    @Test
    @DisplayName("PAUSED가 아니면 resume을 거부한다")
    void PAUSED가_아니면_resume을_거부한다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.resume(CAMPAIGN_ID, ActorType.OWNER, BUSINESS_DATE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 ACTIVE면 다시 resume해도 로그 없이 조용히 성공한다(멱등한 재시도)")
    void 이미_ACTIVE면_다시_resume해도_조용히_성공한다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.resume(CAMPAIGN_ID, ActorType.OWNER, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("ACTIVE 캠페인을 end 하면 ENDED로 바뀌고 상태 변경 이벤트와 종료 이벤트를 함께 발행한다")
    void ACTIVE를_end하면_ENDED로_바뀌고_이벤트_둘을_발행한다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.end(CAMPAIGN_ID, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
        assertThat(captureLog().getActorType()).isEqualTo(ActorType.SYSTEM);
        verify(events).publishEvent(any(CampaignStatusChangedEvent.class));
        verify(events).publishEvent(any(CampaignEndedEvent.class));
    }

    @Test
    @DisplayName("PAUSED 캠페인도 end 할 수 있다")
    void PAUSED도_end할_수_있다() {
        Campaign campaign = pausedCampaign(PauseReason.ADMIN);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.end(CAMPAIGN_ID, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
    }

    @Test
    @DisplayName("ACTIVE나 PAUSED가 아니면 end를 거부한다")
    void ACTIVE나_PAUSED가_아니면_end를_거부한다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.end(CAMPAIGN_ID, BUSINESS_DATE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미 ENDED면 다시 end해도 로그나 이벤트 없이 조용히 성공한다(멱등한 재시도)")
    void 이미_ENDED면_다시_end해도_조용히_성공한다() {
        Campaign campaign = endedCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.end(CAMPAIGN_ID, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("존재하지 않는 캠페인이면 모든 전이를 거부한다")
    void 존재하지_않는_캠페인이면_거부한다() {
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pause(CAMPAIGN_ID, PauseReason.ADMIN, BUSINESS_DATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CampaignStatusLog captureLog() {
        ArgumentCaptor<CampaignStatusLog> captor = ArgumentCaptor.forClass(CampaignStatusLog.class);
        verify(campaignStatusLogRepository).save(captor.capture());
        return captor.getValue();
    }

    private CampaignStatusChangedEvent captureStatusChangedEvent() {
        ArgumentCaptor<CampaignStatusChangedEvent> captor = ArgumentCaptor.forClass(CampaignStatusChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        return captor.getValue();
    }

    private Campaign draftCampaign() {
        Campaign campaign = Campaign.create(
                10L, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                100, LocalTime.of(11, 0), LocalTime.of(14, 0),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.now(FIXED_CLOCK));
        setId(campaign, CAMPAIGN_ID);
        return campaign;
    }

    private Campaign scheduledCampaign() {
        Campaign campaign = draftCampaign();
        campaign.schedule(LocalDateTime.now(FIXED_CLOCK));
        return campaign;
    }

    private Campaign activeCampaign() {
        Campaign campaign = scheduledCampaign();
        campaign.activate(LocalDateTime.now(FIXED_CLOCK));
        return campaign;
    }

    private Campaign pausedCampaign(PauseReason reason) {
        Campaign campaign = activeCampaign();
        campaign.pause(reason, LocalDateTime.now(FIXED_CLOCK));
        return campaign;
    }

    private Campaign endedCampaign() {
        Campaign campaign = activeCampaign();
        campaign.end(LocalDateTime.now(FIXED_CLOCK));
        return campaign;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = BaseTimeEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
