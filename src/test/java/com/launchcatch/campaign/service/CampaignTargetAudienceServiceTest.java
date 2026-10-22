package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
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
import com.launchcatch.ops.contract.PlatformSettingQueryService;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
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

@ExtendWith(MockitoExtension.class)
class CampaignTargetAudienceServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long CAMPAIGN_ID = 501L;
    // 2026-10-20 10:00 KST로 고정. 집행 시작일 경계(오늘 다음 날) 테스트가 이 값을 기준으로 삼는다.
    private static final Clock FIXED_CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 20, 10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            ZoneId.of("Asia/Seoul"));
    private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 21);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private OwnerStatusQuery ownerStatusQuery;
    @Mock
    private PlatformSettingQueryService platformSettingQueryService;

    private CampaignTargetAudienceService service;

    @BeforeEach
    void setUp() {
        service = new CampaignTargetAudienceService(
                campaignRepository, ownerStatusQuery, platformSettingQueryService, FIXED_CLOCK);
        lenient().when(platformSettingQueryService.getMinDailyBudget()).thenReturn(1000L);
    }

    @Test
    @DisplayName("ACTIVE가 아닌 점주는 CAMPAIGN-001로 거부하고 저장소를 건드리지 않는다")
    void ACTIVE가_아닌_점주는_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, audienceRequest()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
    }

    @Test
    @DisplayName("캠페인이 없으면 CAMPAIGN-005로 거부한다")
    void 캠페인이_없으면_거부한다() {
        active();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, audienceRequest()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("본인 가게의 캠페인이 아니면 CAMPAIGN-005로 거부한다")
    void 본인_가게의_캠페인이_아니면_거부한다() {
        active();
        Campaign otherOwnersCampaign = draft(999L);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(otherOwnersCampaign));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, audienceRequest()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    // ── 노출 대상(3단계) ──────────────────────────────────────────────

    @Test
    @DisplayName("노출 대상 세 필드 중 일부만 오면 COMMON-002로 거부한다")
    void 노출_대상_일부만_오면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = new CampaignUpdateRequest(
                1000, null, null, null, null, null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("targetRadius가 허용값이 아니면 COMMON-002로 거부한다")
    void targetRadius가_허용값이_아니면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = audienceRequest(1500, TargetGender.ALL, List.of(AgeGroup.AGE_20S));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("targetAgeGroups에 중복이 있으면 COMMON-002로 거부한다")
    void targetAgeGroups에_중복이_있으면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = audienceRequest(
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S, AgeGroup.AGE_20S));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DRAFT가 아닌 캠페인은 노출 대상 수정을 CAMPAIGN-006으로 거부한다")
    void DRAFT가_아니면_노출_대상_수정을_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        campaign.schedule(LocalDateTime.now());
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, audienceRequest()))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("성공하면 변경된 노출 대상과 현재 예산·기간 값을 응답한다")
    void 노출_대상_성공하면_변경된_값을_응답한다() throws Exception {
        active();
        Campaign campaign = draft(OWNER_ID);
        setId(campaign, CAMPAIGN_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        CampaignUpdateResponse response = service.handle(OWNER_ID, CAMPAIGN_ID,
                audienceRequest(2000, TargetGender.MALE, List.of(AgeGroup.AGE_30S, AgeGroup.AGE_40S)));

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.targetRadius()).isEqualTo(2000);
        assertThat(response.targetGender()).isEqualTo(TargetGender.MALE);
        assertThat(response.targetAgeGroups()).containsExactly(AgeGroup.AGE_30S, AgeGroup.AGE_40S);
        assertThat(response.dailyBudget()).isNull();
        assertThat(response.startDate()).isNull();
        assertThat(response.endDate()).isNull();
    }

    // ── 하루 예산·집행 기간(4단계), DRAFT ──────────────────────────────

    @Test
    @DisplayName("DRAFT에서 예산·기간 세 필드 중 일부만 오면 COMMON-002로 거부한다")
    void DRAFT에서_예산_기간_일부만_오면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = new CampaignUpdateRequest(
                null, null, null, 10_000L, null, null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DRAFT에서 하루 예산이 최소 하루 예산 미만이면 CAMPAIGN-007로 거부한다")
    void DRAFT에서_하루예산이_최소_미만이면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = budgetPeriodRequest(999L, TOMORROW, TOMORROW.plusDays(3));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.DAILY_BUDGET_TOO_LOW));
    }

    @Test
    @DisplayName("DRAFT에서 집행 기간이 1일 미만(같은 날)이면 CAMPAIGN-008로 거부한다")
    void DRAFT에서_집행기간이_같은날이면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = budgetPeriodRequest(10_000L, TOMORROW, TOMORROW);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.PERIOD_TOO_SHORT));
    }

    @Test
    @DisplayName("DRAFT에서 종료일이 시작일보다 이전이면 CAMPAIGN-008로 거부한다")
    void DRAFT에서_종료일이_시작일보다_이전이면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = budgetPeriodRequest(10_000L, TOMORROW.plusDays(3), TOMORROW);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.PERIOD_TOO_SHORT));
    }

    @Test
    @DisplayName("DRAFT에서 집행 시작일이 오늘 다음 날보다 이르면 CAMPAIGN-009로 거부한다")
    void DRAFT에서_시작일이_내일보다_이르면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        LocalDate today = LocalDate.of(2026, 10, 20);
        CampaignUpdateRequest request = budgetPeriodRequest(10_000L, today, today.plusDays(3));

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.START_DATE_TOO_EARLY));
    }

    @Test
    @DisplayName("DRAFT에서 시작일이 오늘 다음 날이면(경계값) 통과한다")
    void DRAFT에서_시작일이_내일이면_통과한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = budgetPeriodRequest(10_000L, TOMORROW, TOMORROW.plusDays(1));

        CampaignUpdateResponse response = service.handle(OWNER_ID, CAMPAIGN_ID, request);

        assertThat(response.dailyBudget()).isEqualTo(10_000L);
        assertThat(response.startDate()).isEqualTo(TOMORROW);
        assertThat(response.endDate()).isEqualTo(TOMORROW.plusDays(1));
    }

    @Test
    @DisplayName("DRAFT에서 예산·기간 성공 시 세 값이 즉시 반영돼 응답에 나온다")
    void DRAFT에서_예산_기간이_즉시_반영된다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = budgetPeriodRequest(5000L, TOMORROW, TOMORROW.plusDays(3));

        CampaignUpdateResponse response = service.handle(OWNER_ID, CAMPAIGN_ID, request);

        assertThat(response.dailyBudget()).isEqualTo(5000L);
        assertThat(response.startDate()).isEqualTo(TOMORROW);
        assertThat(response.endDate()).isEqualTo(TOMORROW.plusDays(3));
        assertThat(campaign.getPendingDailyBudget()).isNull();
    }

    // ── 하루 예산(4단계), ACTIVE ────────────────────────────────────

    @Test
    @DisplayName("ACTIVE에서 집행 기간 필드가 오면 값이 같아도 CAMPAIGN-006으로 거부한다")
    void ACTIVE에서_집행기간이_오면_거부한다() {
        active();
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = budgetPeriodRequest(10_000L, campaign.getStartDate(), campaign.getEndDate());

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("ACTIVE에서 하루 예산이 최소 하루 예산 미만이면 CAMPAIGN-007로 거부한다")
    void ACTIVE에서_하루예산이_최소_미만이면_거부한다() {
        active();
        Campaign campaign = activeCampaign();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = new CampaignUpdateRequest(null, null, null, 500L, null, null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.DAILY_BUDGET_TOO_LOW));
    }

    @Test
    @DisplayName("ACTIVE에서 하루 예산 변경 성공 시 예약 값으로만 저장되고 응답은 바뀌기 전 값을 보여준다")
    void ACTIVE에서_하루예산이_예약값으로만_저장된다() {
        active();
        Campaign campaign = activeCampaign();
        Long beforeBudget = campaign.getDailyBudget();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = new CampaignUpdateRequest(null, null, null, 7000L, null, null);

        CampaignUpdateResponse response = service.handle(OWNER_ID, CAMPAIGN_ID, request);

        assertThat(response.dailyBudget()).isEqualTo(beforeBudget);
        assertThat(campaign.getDailyBudget()).isEqualTo(beforeBudget);
        assertThat(campaign.getPendingDailyBudget()).isEqualTo(7000L);
    }

    @Test
    @DisplayName("SCHEDULED 캠페인에 하루 예산이 오면 CAMPAIGN-006으로 거부한다")
    void SCHEDULED에서_하루예산이_오면_거부한다() {
        active();
        Campaign campaign = draft(OWNER_ID);
        campaign.schedule(LocalDateTime.now());
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        CampaignUpdateRequest request = new CampaignUpdateRequest(null, null, null, 10_000L, null, null);

        assertThatThrownBy(() -> service.handle(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    private void active() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
    }

    private Campaign draft(Long ownerId) {
        return Campaign.create(
                ownerId, 17L,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
    }

    private Campaign activeCampaign() {
        Campaign campaign = Campaign.create(
                OWNER_ID, 17L,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10_000L, TOMORROW, TOMORROW.plusDays(3),
                LocalDateTime.now());
        campaign.schedule(LocalDateTime.now());
        campaign.activate(LocalDateTime.now());
        return campaign;
    }

    private CampaignUpdateRequest audienceRequest() {
        return audienceRequest(1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S));
    }

    private CampaignUpdateRequest audienceRequest(
            Integer targetRadius, TargetGender targetGender, List<AgeGroup> targetAgeGroups) {
        return new CampaignUpdateRequest(targetRadius, targetGender, targetAgeGroups, null, null, null);
    }

    private CampaignUpdateRequest budgetPeriodRequest(Long dailyBudget, LocalDate startDate, LocalDate endDate) {
        return new CampaignUpdateRequest(null, null, null, dailyBudget, startDate, endDate);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
