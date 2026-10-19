package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignActivateResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.poster.dto.PosterModerationOutcome;
import com.launchcatch.campaign.poster.entity.ModerationFailReason;
import com.launchcatch.campaign.poster.entity.ModerationResult;
import com.launchcatch.campaign.poster.service.PosterModerationService;
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
 * campaignStatusService는 스텁하지 않고 실제 구현을 쓴다. schedule()의 상태 전이와 로그는
 * CampaignStatusServiceTest가 이미 검증하므로 여기서는 점주 상태 확인, 본인 소유 확인, DRAFT
 * 여부 확인, 검수 결과에 따른 분기만 본다(#561).
 */
@ExtendWith(MockitoExtension.class)
class CampaignActivationServiceTest {

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
    private PosterModerationService posterModerationService;
    @Mock
    private OwnerStatusQuery ownerStatusQuery;

    private CampaignActivationService service;

    @BeforeEach
    void setUp() {
        CampaignStatusService campaignStatusService = new CampaignStatusService(
                campaignRepository, campaignStatusLogRepository, storeCampaignSummaryUpdater, events, FIXED_CLOCK);
        service = new CampaignActivationService(
                campaignRepository, campaignStatusService, posterModerationService, ownerStatusQuery);
    }

    @Test
    @DisplayName("ACTIVE가 아닌 점주는 CAMPAIGN-001로 거부하고 저장소를 건드리지 않는다")
    void ACTIVE가_아닌_점주는_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
    }

    @Test
    @DisplayName("캠페인이 없으면 CAMPAIGN-005로 거부한다")
    void 캠페인이_없으면_거부한다() {
        active();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("본인 가게의 캠페인이 아니면 CAMPAIGN-005로 거부한다")
    void 본인_가게의_캠페인이_아니면_거부한다() {
        active();
        Campaign otherOwnersCampaign = draftCampaign(999L);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(otherOwnersCampaign));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("DRAFT가 아니면 CAMPAIGN-006으로 거부하고 검수를 부르지 않는다")
    void DRAFT가_아니면_거부한다() {
        active();
        Campaign campaign = activeCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
        verify(posterModerationService, never()).moderate(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("포스터가 없으면 PosterModerationService가 던지는 POSTER_NOT_SAVED가 그대로 올라간다")
    void 포스터가_없으면_거부한다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenThrow(new CampaignException(PosterErrorCode.POSTER_NOT_SAVED));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_NOT_SAVED));
    }

    @Test
    @DisplayName("검수를 통과하면 SCHEDULED로 바뀌고 사유는 비어 있다")
    void 검수를_통과하면_SCHEDULED가_된다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenReturn(new PosterModerationOutcome(ModerationResult.PASS, List.of()));

        CampaignActivateResponse response = service.activate(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.status()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(response.reasons()).isEmpty();
    }

    @Test
    @DisplayName("검수에 실패하면 DRAFT로 남고 사유를 모두 담아 응답한다")
    void 검수에_실패하면_DRAFT로_남는다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        List<ModerationFailReason> reasons = List.of(
                ModerationFailReason.AD_LABEL_MISSING, ModerationFailReason.RESOLUTION_TOO_LOW);
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenReturn(new PosterModerationOutcome(ModerationResult.FAIL, reasons));

        CampaignActivateResponse response = service.activate(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(response.reasons()).containsExactlyElementsOf(reasons);
    }

    private void active() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
    }

    private Campaign draftCampaign(Long ownerId) {
        Campaign campaign = Campaign.create(
                ownerId, 20L,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
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
