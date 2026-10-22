package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.launchcatch.store.contract.StoreCampaignStatus;
import com.launchcatch.store.contract.StoreCampaignSummaryCommand;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
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
    private StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;
    @Mock
    private ApplicationEventPublisher events;

    private CampaignStatusService service;

    @BeforeEach
    void setUp() {
        service = new CampaignStatusService(
                campaignRepository, campaignStatusLogRepository, storeCampaignSummaryUpdater, events, FIXED_CLOCK);
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
    @DisplayName("precondition이 있는 schedule은 잠금을 잡은 뒤 상태를 바꾸기 전에 그것을 실행한다")
    void precondition이_있는_schedule은_상태를_바꾸기_전에_그것을_실행한다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.schedule(CAMPAIGN_ID, () ->
                assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT));

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
    }

    @Test
    @DisplayName("precondition이 실패하면 schedule하지 않고 그 예외를 그대로 올린다")
    void precondition이_실패하면_schedule하지_않는다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        RuntimeException preconditionFailure = new IllegalArgumentException("precondition failed");

        assertThatThrownBy(() -> service.schedule(CAMPAIGN_ID, () -> {
            throw preconditionFailure;
        })).isSameAs(preconditionFailure);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        verify(campaignStatusLogRepository, never()).save(any());
    }

    /*
     * precondition이 있는 schedule은 이미 SCHEDULED여도 멱등하게 조용히 성공하지 않고
     * 거부한다(#576 코드래빗 리뷰로 찾음). 멱등하게 반환하면 그 요청이 precondition을
     * 건너뛴 채 성공 응답을 받게 된다.
     */
    @Test
    @DisplayName("precondition이 있는 schedule은 이미 SCHEDULED면 거부하고 precondition을 부르지 않는다")
    void precondition이_있는_schedule은_이미_SCHEDULED면_거부한다() {
        Campaign campaign = scheduledCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        Runnable precondition = mock(Runnable.class);

        assertThatThrownBy(() -> service.schedule(CAMPAIGN_ID, precondition))
                .isInstanceOf(IllegalStateException.class);

        verify(precondition, never()).run();
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
    @DisplayName("영업일을 받는 activate 는 ACTIVE 로 바꾸고 가게 요약도 갱신한다(#569)")
    void 영업일을_받는_activate_는_가게_요약도_갱신한다() {
        Campaign campaign = scheduledCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.activate(CAMPAIGN_ID, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(captureLog().getActorType()).isEqualTo(ActorType.SYSTEM);
        verify(storeCampaignSummaryUpdater).update(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("이미 ACTIVE 면 영업일을 받는 activate 를 다시 불러도 가게 요약을 갱신하지 않는다(멱등)")
    void 이미_ACTIVE면_영업일을_받는_activate_는_요약을_갱신하지_않는다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.activate(CAMPAIGN_ID, BUSINESS_DATE);

        verify(storeCampaignSummaryUpdater, never()).update(anyLong(), anyLong(), any());
        verify(campaignStatusLogRepository, never()).save(any());
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

    /*
     * 점주 취소(#597)다. schedule 과 같이 이벤트를 내지 않고 가게 요약도 갱신하지 않는다.
     * 다른 도메인은 SCHEDULED 를 그때그때 읽기만 해서 되돌릴 것이 없다.
     */
    @Test
    @DisplayName("SCHEDULED 캠페인을 cancel 하면 DRAFT 로 바뀌고 OWNER 행위자로 로그가 남고 이벤트는 없다")
    void SCHEDULED를_cancel하면_DRAFT로_바뀐다() {
        Campaign campaign = scheduledCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.cancel(CAMPAIGN_ID);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        CampaignStatusLog log = captureLog();
        assertThat(log.getFromStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(log.getToStatus()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(log.getActorType()).isEqualTo(ActorType.OWNER);
        verify(events, never()).publishEvent(any());
        verifyNoInteractions(storeCampaignSummaryUpdater);
    }

    @Test
    @DisplayName("이미 DRAFT면 다시 cancel해도 로그 없이 조용히 성공한다(멱등한 재시도)")
    void 이미_DRAFT면_다시_cancel해도_조용히_성공한다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.cancel(CAMPAIGN_ID);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        verify(campaignStatusLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("집행이 시작됐거나 끝난 캠페인은 cancel을 거부한다")
    void 집행이_시작됐으면_cancel을_거부한다() {
        for (Campaign campaign : List.of(activeCampaign(), pausedCampaign(PauseReason.OWNER), endedCampaign())) {
            when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

            assertThatThrownBy(() -> service.cancel(CAMPAIGN_ID)).isInstanceOf(IllegalStateException.class);
        }
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
        verify(storeCampaignSummaryUpdater).update(20L, CAMPAIGN_ID, new StoreCampaignSummaryCommand(
                BUSINESS_DATE, StoreCampaignStatus.PAUSED, StoreDiscountTargetType.ALL, StoreDiscountType.AMOUNT,
                10, null, LocalTime.of(11, 0), LocalTime.of(14, 0)));
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
        verify(storeCampaignSummaryUpdater, never()).update(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("pause는 pendingDailyBudget을 지우지 않는다(#330)")
    void pause는_pendingDailyBudget을_지우지_않는다() {
        Campaign campaign = activeCampaign();
        campaign.reserveDailyBudget(7000L);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pause(CAMPAIGN_ID, PauseReason.OWNER, BUSINESS_DATE);

        assertThat(campaign.getPendingDailyBudget()).isEqualTo(7000L);
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
        verify(storeCampaignSummaryUpdater, never()).update(anyLong(), anyLong(), any());
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
        verify(storeCampaignSummaryUpdater, never()).update(anyLong(), anyLong(), any());
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
        verify(storeCampaignSummaryUpdater).update(20L, CAMPAIGN_ID, new StoreCampaignSummaryCommand(
                BUSINESS_DATE, StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, StoreDiscountType.AMOUNT,
                10, null, LocalTime.of(11, 0), LocalTime.of(14, 0)));
    }

    @Test
    @DisplayName("PAUSED가 아니면 resume을 거부한다")
    void PAUSED가_아니면_resume을_거부한다() {
        Campaign campaign = draftCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.resume(CAMPAIGN_ID, ActorType.OWNER, BUSINESS_DATE))
                .isInstanceOf(IllegalStateException.class);
        verify(storeCampaignSummaryUpdater, never()).update(anyLong(), anyLong(), any());
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
        verify(storeCampaignSummaryUpdater, never()).update(anyLong(), anyLong(), any());
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
    @DisplayName("점주가 end 하면 행위자는 OWNER로 남고 가게 요약을 ENDED로 갱신한다(#600)")
    void 점주가_end하면_행위자가_OWNER이고_가게_요약을_갱신한다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.end(CAMPAIGN_ID, ActorType.OWNER, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
        assertThat(captureLog().getActorType()).isEqualTo(ActorType.OWNER);
        verify(storeCampaignSummaryUpdater).update(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("PAUSED 캠페인도 end 할 수 있다")
    void PAUSED도_end할_수_있다() {
        Campaign campaign = pausedCampaign(PauseReason.ADMIN);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.end(CAMPAIGN_ID, BUSINESS_DATE);

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
        assertThat(campaign.getPausedReason()).isNull();
    }

    @Test
    @DisplayName("end는 pendingDailyBudget을 지운다(#330)")
    void end는_pendingDailyBudget을_지운다() {
        Campaign campaign = activeCampaign();
        campaign.reserveDailyBudget(7000L);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.end(CAMPAIGN_ID, BUSINESS_DATE);

        assertThat(campaign.getPendingDailyBudget()).isNull();
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

    @Test
    @DisplayName("ACTIVE 캠페인에 pauseFromFunding을 보내면 PAUSED(NO_POINTS)로 바뀌고 SYSTEM으로 기록된다")
    void ACTIVE에_pauseFromFunding을_보내면_PAUSED_NO_POINTS로_바뀐다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pauseFromFunding(CAMPAIGN_ID, BUSINESS_DATE, "cause=DAILY");

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(campaign.getPausedReason()).isEqualTo(PauseReason.NO_POINTS);
        CampaignStatusLog log = captureLog();
        assertThat(log.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(log.getReason()).isEqualTo("cause=DAILY");
        CampaignStatusChangedEvent event = captureStatusChangedEvent();
        assertThat(event.servable()).isFalse();
        verify(storeCampaignSummaryUpdater, never()).update(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("이미 PAUSED(NO_POINTS)면 다시 pauseFromFunding해도 로그 없이 조용히 성공한다(멱등)")
    void 이미_PAUSED_NO_POINTS면_pauseFromFunding해도_조용히_성공한다() {
        Campaign campaign = pausedCampaign(PauseReason.NO_POINTS);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pauseFromFunding(CAMPAIGN_ID, BUSINESS_DATE, "cause=DAILY");

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("PAUSED(ADMIN)이면 pauseFromFunding을 무시한다(예외 대신 조용히 넘긴다)")
    void PAUSED_ADMIN이면_pauseFromFunding을_무시한다() {
        Campaign campaign = pausedCampaign(PauseReason.ADMIN);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pauseFromFunding(CAMPAIGN_ID, BUSINESS_DATE, "cause=DAILY");

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(campaign.getPausedReason()).isEqualTo(PauseReason.ADMIN);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("ENDED면 pauseFromFunding을 무시한다")
    void ENDED면_pauseFromFunding을_무시한다() {
        Campaign campaign = endedCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.pauseFromFunding(CAMPAIGN_ID, BUSINESS_DATE, "cause=DAILY");

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("PAUSED(NO_POINTS) 캠페인에 resumeFromFunding을 보내면 ACTIVE로 바뀐다")
    void PAUSED_NO_POINTS에_resumeFromFunding을_보내면_ACTIVE로_바뀐다() {
        Campaign campaign = pausedCampaign(PauseReason.NO_POINTS);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.resumeFromFunding(CAMPAIGN_ID, BUSINESS_DATE, "cause=RECHARGE");

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        CampaignStatusLog log = captureLog();
        assertThat(log.getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(log.getReason()).isEqualTo("cause=RECHARGE");
        CampaignStatusChangedEvent event = captureStatusChangedEvent();
        assertThat(event.servable()).isTrue();
        verify(storeCampaignSummaryUpdater, never()).update(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("이미 ACTIVE면 resumeFromFunding해도 로그 없이 조용히 성공한다(멱등)")
    void 이미_ACTIVE면_resumeFromFunding해도_조용히_성공한다() {
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.resumeFromFunding(CAMPAIGN_ID, BUSINESS_DATE, "cause=RECHARGE");

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("PAUSED(OWNER)면 resumeFromFunding을 무시한다")
    void PAUSED_OWNER면_resumeFromFunding을_무시한다() {
        Campaign campaign = pausedCampaign(PauseReason.OWNER);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.resumeFromFunding(CAMPAIGN_ID, BUSINESS_DATE, "cause=RECHARGE");

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(campaign.getPausedReason()).isEqualTo(PauseReason.OWNER);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("ENDED면 resumeFromFunding을 무시한다")
    void ENDED면_resumeFromFunding을_무시한다() {
        Campaign campaign = endedCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        service.resumeFromFunding(CAMPAIGN_ID, BUSINESS_DATE, "cause=RECHARGE");

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
        verify(campaignStatusLogRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
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
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
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
