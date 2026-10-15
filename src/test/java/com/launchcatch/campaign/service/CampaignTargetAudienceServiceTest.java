package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.dto.CampaignUpdateRequest;
import com.launchcatch.campaign.dto.CampaignUpdateResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CampaignTargetAudienceServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long CAMPAIGN_ID = 501L;

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private OwnerStatusQuery ownerStatusQuery;

    private CampaignTargetAudienceService service;

    @BeforeEach
    void setUp() {
        service = new CampaignTargetAudienceService(campaignRepository, ownerStatusQuery);
    }

    @Test
    @DisplayName("ACTIVE가 아닌 점주는 CAMPAIGN-001로 거부하고 저장소를 건드리지 않는다")
    void ACTIVE가_아니면_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
    }

    @Test
    @DisplayName("targetRadius가 허용값이 아니면 COMMON-002로 거부한다")
    void targetRadius가_허용값이_아니면_거부한다() {
        active();
        CampaignUpdateRequest request = request(1500, TargetGender.ALL, List.of(AgeGroup.AGE_20S), null, null, null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("targetAgeGroups에 중복이 있으면 COMMON-002로 거부한다")
    void targetAgeGroups에_중복이_있으면_거부한다() {
        active();
        CampaignUpdateRequest request = request(1000, TargetGender.ALL,
                List.of(AgeGroup.AGE_20S, AgeGroup.AGE_20S), null, null, null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dailyBudget이 오면 아직 지원하지 않아 COMMON-002로 거부한다")
    void dailyBudget이_오면_거부한다() {
        active();
        CampaignUpdateRequest request = request(1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S), 10_000L, null, null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("startDate가 오면 아직 지원하지 않아 COMMON-002로 거부한다")
    void startDate가_오면_거부한다() {
        active();
        CampaignUpdateRequest request = request(1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, LocalDate.of(2026, 11, 1), null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("endDate가 오면 아직 지원하지 않아 COMMON-002로 거부한다")
    void endDate가_오면_거부한다() {
        active();
        CampaignUpdateRequest request = request(1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, LocalDate.of(2026, 11, 30));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("캠페인이 없으면 CAMPAIGN-005로 거부한다")
    void 캠페인이_없으면_거부한다() {
        active();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("본인 가게의 캠페인이 아니면 CAMPAIGN-005로 거부한다")
    void 본인_가게의_캠페인이_아니면_거부한다() throws Exception {
        active();
        Campaign otherOwnersCampaign = draft(999L);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(otherOwnersCampaign));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("DRAFT가 아닌 캠페인은 CAMPAIGN-006으로 거부한다")
    void DRAFT가_아니면_거부한다() throws Exception {
        active();
        Campaign campaign = draft(OWNER_ID);
        campaign.schedule(LocalDateTime.now());
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("성공하면 변경된 노출 대상과 현재 예산·기간 값을 응답한다")
    void 성공하면_변경된_값을_응답한다() throws Exception {
        active();
        Campaign campaign = draft(OWNER_ID);
        setId(campaign, CAMPAIGN_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CampaignUpdateResponse response = service.handle(OWNER_ID, CAMPAIGN_ID,
                request(2000, TargetGender.MALE, List.of(AgeGroup.AGE_30S, AgeGroup.AGE_40S), null, null, null));

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.targetRadius()).isEqualTo(2000);
        assertThat(response.targetGender()).isEqualTo(TargetGender.MALE);
        assertThat(response.targetAgeGroups()).containsExactly(AgeGroup.AGE_30S, AgeGroup.AGE_40S);
        assertThat(response.dailyBudget()).isNull();
        assertThat(response.startDate()).isNull();
        assertThat(response.endDate()).isNull();
    }

    private void active() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
    }

    private Campaign draft(Long ownerId) {
        return Campaign.create(
                ownerId, 17L,
                DiscountTargetType.ALL, DiscountType.PERCENT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
    }

    private CampaignUpdateRequest request() {
        return request(1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S), null, null, null);
    }

    private CampaignUpdateRequest request(
            Integer targetRadius, TargetGender targetGender, List<AgeGroup> targetAgeGroups,
            Long dailyBudget, LocalDate startDate, LocalDate endDate) {
        return new CampaignUpdateRequest(targetRadius, targetGender, targetAgeGroups, dailyBudget, startDate, endDate);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
