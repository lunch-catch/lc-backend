package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.PauseReason;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignStatusResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/*
 * campaignStatusService는 스텁하지 않고 실제 구현을 쓴다. pause/resume의 상태 전이·로그·이벤트는
 * CampaignStatusServiceTest가 이미 검증하므로 여기서는 점주 상태 확인, 본인 소유 확인,
 * IllegalStateException -> CampaignException 변환만 본다(#468).
 */
@ExtendWith(MockitoExtension.class)
class CampaignPauseResumeServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long CAMPAIGN_ID = 501L;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 20, 10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul"));

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private CampaignStatusLogRepository campaignStatusLogRepository;
    @Mock
    private StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private OwnerStatusQuery ownerStatusQuery;

    private CampaignPauseResumeService service;

    @BeforeEach
    void setUp() {
        CampaignStatusService campaignStatusService = new CampaignStatusService(
                campaignRepository, campaignStatusLogRepository, storeCampaignSummaryUpdater, events, FIXED_CLOCK);
        service = new CampaignPauseResumeService(
                campaignRepository, campaignStatusService, ownerStatusQuery, FIXED_CLOCK);
    }

    @Test
    @DisplayName("ACTIVE가 아닌 점주는 CAMPAIGN-001로 거부하고 저장소를 건드리지 않는다")
    void ACTIVE가_아닌_점주는_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.pause(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
    }

    @Test
    @DisplayName("캠페인이 없으면 CAMPAIGN-005로 거부한다")
    void 캠페인이_없으면_거부한다() {
        active();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pause(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("본인 가게의 캠페인이 아니면 CAMPAIGN-005로 거부한다")
    void 본인_가게의_캠페인이_아니면_거부한다() {
        active();
        Campaign otherOwnersCampaign = activeCampaign(999L);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(otherOwnersCampaign));

        assertThatThrownBy(() -> service.pause(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("ACTIVE가 아니면 pause를 CAMPAIGN-006으로 거부한다")
    void ACTIVE가_아니면_pause를_거부한다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.pause(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("ACTIVE 캠페인을 중단하면 PAUSED를 응답한다")
    void ACTIVE를_중단하면_PAUSED를_응답한다() {
        active();
        Campaign campaign = activeCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CampaignStatusResponse response = service.pause(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.status()).isEqualTo(CampaignStatus.PAUSED);
    }

    @Test
    @DisplayName("이미 점주 중단으로 PAUSED면 다시 중단해도 PAUSED를 응답한다(멱등)")
    void 이미_PAUSED면_다시_중단해도_PAUSED를_응답한다() {
        active();
        Campaign campaign = pausedCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CampaignStatusResponse response = service.pause(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.status()).isEqualTo(CampaignStatus.PAUSED);
    }

    @Test
    @DisplayName("PAUSED가 아니면 resume을 CAMPAIGN-006으로 거부한다")
    void PAUSED가_아니면_resume을_거부한다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.resume(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("PAUSED 캠페인을 재개하면 ACTIVE를 응답한다")
    void PAUSED를_재개하면_ACTIVE를_응답한다() {
        active();
        Campaign campaign = pausedCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CampaignStatusResponse response = service.resume(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.status()).isEqualTo(CampaignStatus.ACTIVE);
    }

    private void active() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
    }

    private Campaign draftCampaign(Long ownerId) {
        Campaign campaign = Campaign.create(
                ownerId, 20L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, LocalDateTime.now(FIXED_CLOCK).toLocalDate().plusDays(1),
                LocalDateTime.now(FIXED_CLOCK).toLocalDate().plusDays(4),
                LocalDateTime.now(FIXED_CLOCK));
        setId(campaign, CAMPAIGN_ID);
        return campaign;
    }

    private Campaign activeCampaign(Long ownerId) {
        Campaign campaign = draftCampaign(ownerId);
        campaign.schedule(LocalDateTime.now(FIXED_CLOCK));
        campaign.activate(LocalDateTime.now(FIXED_CLOCK));
        return campaign;
    }

    private Campaign pausedCampaign(Long ownerId) {
        Campaign campaign = activeCampaign(ownerId);
        campaign.pause(PauseReason.OWNER, LocalDateTime.now(FIXED_CLOCK));
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
