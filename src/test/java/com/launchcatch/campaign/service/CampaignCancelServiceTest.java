package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
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
 * campaignStatusService는 스텁하지 않고 실제 구현을 쓴다. cancel의 상태 전이와 로그는
 * CampaignStatusServiceTest가 검증하므로 여기서는 점주 상태 확인, 본인 소유 확인,
 * IllegalStateException -> CampaignException 변환과 응답만 본다(#597).
 */
@ExtendWith(MockitoExtension.class)
class CampaignCancelServiceTest {

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

    private CampaignCancelService service;

    @BeforeEach
    void setUp() {
        CampaignStatusService campaignStatusService = new CampaignStatusService(
                campaignRepository, campaignStatusLogRepository, storeCampaignSummaryUpdater, events, FIXED_CLOCK);
        service = new CampaignCancelService(campaignRepository, campaignStatusService, ownerStatusQuery);
    }

    @Test
    @DisplayName("ACTIVE가 아닌 점주는 CAMPAIGN-001로 거부하고 캠페인을 읽지 않는다")
    void ACTIVE가_아닌_점주는_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.SUSPENDED));

        assertThatThrownBy(() -> service.cancel(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
        verifyNoInteractions(campaignRepository);
    }

    @Test
    @DisplayName("캠페인이 없으면 CAMPAIGN-005로 거부한다")
    void 캠페인이_없으면_거부한다() {
        active();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("본인 캠페인이 아니면 상태를 바꾸지 않고 CAMPAIGN-005로 거부한다")
    void 본인_캠페인이_아니면_거부한다() {
        active();
        Campaign others = scheduledCampaign(999L);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(others));

        assertThatThrownBy(() -> service.cancel(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        assertThat(others.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
    }

    /*
     * 00:00 배치가 먼저 ACTIVE 로 바꾼 경우도 이 분기다. 취소와 배치는 같은 행 잠금으로 줄을 서서,
     * 뒤에 온 취소는 ACTIVE 를 보고 거부된다.
     */
    @Test
    @DisplayName("집행이 시작된 캠페인은 CAMPAIGN-006으로 거부한다")
    void 집행이_시작됐으면_거부한다() {
        active();
        Campaign campaign = scheduledCampaign(OWNER_ID);
        campaign.activate(LocalDateTime.now(FIXED_CLOCK));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.cancel(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    @DisplayName("SCHEDULED 캠페인을 취소하면 DRAFT를 응답한다")
    void SCHEDULED를_취소하면_DRAFT를_응답한다() {
        active();
        Campaign campaign = scheduledCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CampaignStatusResponse response = service.cancel(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    @DisplayName("이미 DRAFT면 다시 취소해도 DRAFT를 응답한다(멱등)")
    void 이미_DRAFT면_다시_취소해도_DRAFT를_응답한다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThat(service.cancel(OWNER_ID, CAMPAIGN_ID).status()).isEqualTo(CampaignStatus.DRAFT);
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

    private Campaign scheduledCampaign(Long ownerId) {
        Campaign campaign = draftCampaign(ownerId);
        campaign.schedule(LocalDateTime.now(FIXED_CLOCK));
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
