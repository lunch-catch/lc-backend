package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.poster.service.PosterModerationService;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.repository.CampaignStatusLogRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
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
import org.springframework.context.ApplicationEventPublisher;

/*
 * campaignStatusService는 스텁하지 않고 실제 구현을 쓴다. schedule()의 상태 전이와 로그는
 * CampaignStatusServiceTest가 이미 검증하므로 여기서는 점주 상태 확인, 본인 소유 확인, DRAFT
 * 여부 확인, 요청 빈도 제한, 검수 결과에 따른 분기, 포스터 변경 재확인만 본다(#561).
 *
 * 사전 확인(findById)과 schedule() 내부 전이(findByIdForUpdate)는 서로 다른 저장소 메서드라
 * 각각 따로 스텁한다. 둘이 다른 캠페인 상태를 돌려주는 조합으로 "사전 확인 뒤 실제 전이
 * 사이에 상태가 바뀌는 경쟁"도 본다. posterRepository.findByCampaignId는 검수 전과
 * schedule()의 precondition(캠페인 잠금 뒤) 두 번 불려서, 포스터가 안 바뀐 테스트는 같은
 * updatedAt을 두 번 돌려주고 바뀌는 테스트만 다른 값을 순서대로 돌려준다.
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
    private PosterRepository posterRepository;
    @Mock
    private CampaignActivationRateLimiter rateLimiter;
    @Mock
    private OwnerStatusQuery ownerStatusQuery;

    private CampaignActivationService service;

    @BeforeEach
    void setUp() {
        CampaignStatusService campaignStatusService = new CampaignStatusService(
                campaignRepository, campaignStatusLogRepository, storeCampaignSummaryUpdater, events, FIXED_CLOCK);
        service = new CampaignActivationService(
                campaignRepository, campaignStatusService, posterModerationService, posterRepository,
                rateLimiter, ownerStatusQuery, FIXED_CLOCK);
    }

    private Poster posterUpdatedAt(LocalDateTime updatedAt) {
        Poster poster = mock(Poster.class);
        when(poster.getUpdatedAt()).thenReturn(updatedAt);
        return poster;
    }

    @Test
    @DisplayName("ACTIVE가 아닌 점주는 CAMPAIGN-001로 거부하고 저장소를 건드리지 않는다")
    void ACTIVE가_아닌_점주는_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
        verify(rateLimiter, never()).checkLimit(OWNER_ID);
    }

    @Test
    @DisplayName("캠페인이 없으면 CAMPAIGN-005로 거부한다")
    void 캠페인이_없으면_거부한다() {
        active();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("본인 가게의 캠페인이 아니면 CAMPAIGN-005로 거부한다")
    void 본인_가게의_캠페인이_아니면_거부한다() {
        active();
        Campaign otherOwnersCampaign = draftCampaign(999L);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(otherOwnersCampaign));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("하루 예산이나 집행 기간이 비어 있으면 CAMPAIGN-015로 거부하고 요청 빈도를 세거나 검수를 부르지 않는다(#875)")
    void 필수값이_비어_있으면_거부한다() {
        active();
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        Campaign noBudget = draftCampaignWith(null, today.plusDays(1), today.plusDays(4));
        Campaign noStart = draftCampaignWith(10_000L, null, today.plusDays(4));
        Campaign noEnd = draftCampaignWith(10_000L, today.plusDays(1), null);
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(noBudget), Optional.of(noStart), Optional.of(noEnd));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                    .isInstanceOfSatisfying(CampaignException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(CampaignErrorCode.ACTIVATION_REQUIREMENTS_MISSING));
        }

        verify(rateLimiter, never()).checkLimit(OWNER_ID);
        verify(posterModerationService, never()).moderate(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("집행 종료일이 오늘이거나 지났으면 CAMPAIGN-016으로 거부하고 요청 빈도를 세거나 검수를 부르지 않는다(#875)")
    void 종료일이_오늘이거나_지났으면_거부한다() {
        active();
        LocalDate today = LocalDate.now(FIXED_CLOCK);
        Campaign endsToday = draftCampaignWith(10_000L, today.minusDays(3), today);
        Campaign alreadyEnded = draftCampaignWith(10_000L, today.minusDays(5), today.minusDays(1));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(endsToday), Optional.of(alreadyEnded));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                    .isInstanceOfSatisfying(CampaignException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(CampaignErrorCode.PERIOD_ENDED_BEFORE_START));
        }

        verify(rateLimiter, never()).checkLimit(OWNER_ID);
        verify(posterModerationService, never()).moderate(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("DRAFT가 아니면 CAMPAIGN-006으로 거부하고 요청 빈도를 세거나 검수를 부르지 않는다")
    void DRAFT가_아니면_거부한다() {
        active();
        Campaign campaign = activeCampaign(OWNER_ID);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
        verify(rateLimiter, never()).checkLimit(OWNER_ID);
        verify(posterModerationService, never()).moderate(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("요청 빈도 제한에 걸리면 그 예외가 그대로 올라가고 검수를 부르지 않는다")
    void 요청_빈도_제한에_걸리면_거부한다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        doThrow(new CampaignException(CampaignErrorCode.ACTIVATION_RATE_LIMITED))
                .when(rateLimiter).checkLimit(OWNER_ID);

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.ACTIVATION_RATE_LIMITED));
        verify(posterModerationService, never()).moderate(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("포스터가 없으면 PosterModerationService가 던지는 POSTER_NOT_SAVED가 그대로 올라간다")
    void 포스터가_없으면_거부한다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
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
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(OWNER_ID)));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(OWNER_ID)));
        Poster poster = posterUpdatedAt(LocalDateTime.now(FIXED_CLOCK));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster));
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenReturn(new PosterModerationOutcome(ModerationResult.PASS, List.of()));

        CampaignActivateResponse response = service.activate(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.status()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(response.reasons()).isEmpty();
        verify(rateLimiter).checkLimit(OWNER_ID);
    }

    @Test
    @DisplayName("검수에 실패하면 DRAFT로 남고 사유를 모두 담아 응답한다")
    void 검수에_실패하면_DRAFT로_남는다() {
        active();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(OWNER_ID)));
        List<ModerationFailReason> reasons = List.of(
                ModerationFailReason.FORBIDDEN_EXPRESSION, ModerationFailReason.RESOLUTION_TOO_LOW);
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenReturn(new PosterModerationOutcome(ModerationResult.FAIL, reasons));

        CampaignActivateResponse response = service.activate(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(response.reasons()).containsExactlyElementsOf(reasons);
    }

    /*
     * 사전 확인(findById)은 DRAFT를 보고 통과시켰지만, 검수가 끝나고 schedule()을 부르는
     * 순간에는(findByIdForUpdate) 이미 다른 요청이 먼저 ACTIVE로 바꿔 놓은 상황을 흉내낸다.
     * 중복 전이는 이 레이스에서도 막혀야 한다.
     */
    @Test
    @DisplayName("사전 확인과 schedule 사이에 상태가 바뀌면 CAMPAIGN-006으로 거부한다")
    void 사전_확인과_전이_사이에_상태가_바뀌면_거부한다() {
        active();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(OWNER_ID)));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(activeCampaign(OWNER_ID)));
        Poster poster = posterUpdatedAt(LocalDateTime.now(FIXED_CLOCK));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster));
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenReturn(new PosterModerationOutcome(ModerationResult.PASS, List.of()));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    /*
     * 사전 확인 뒤 다른 요청이 먼저 SCHEDULED로 전이를 끝낸 상황이다. schedule(Long, Runnable)이
     * 이 경우 멱등하게 조용히 성공하면, 이 요청은 자신의 포스터 재확인(precondition)을 건너뛴
     * 채 성공 응답을 받게 된다(#576 코드래빗 리뷰로 찾음). 거부돼야 한다.
     */
    @Test
    @DisplayName("사전 확인 뒤 이미 SCHEDULED가 되어 있으면 CAMPAIGN-006으로 거부하고 포스터를 다시 확인하지 않는다")
    void 사전_확인_뒤_이미_SCHEDULED면_거부한다() {
        active();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(OWNER_ID)));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(scheduledCampaign(OWNER_ID)));
        Poster poster = posterUpdatedAt(LocalDateTime.now(FIXED_CLOCK));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster));
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenReturn(new PosterModerationOutcome(ModerationResult.PASS, List.of()));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    /*
     * 검수가 본 포스터(moderate() 부르기 전 캠페인 잠금 없이 읽은 값)와, schedule()이 캠페인
     * 잠금을 잡은 뒤(precondition) 다시 읽은 포스터가 다르다. 점주가 검수 중에 포스터를 새로
     * 올린 경우를 흉내낸다(코드래빗 리뷰로 찾음, #561). 그 PASS 결과로 전이해서는 안 된다.
     */
    @Test
    @DisplayName("검수 뒤 포스터가 바뀌면 CAMPAIGN-006으로 거부하고 전이하지 않는다")
    void 검수_뒤_포스터가_바뀌면_거부한다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        Poster posterAtModeration = posterUpdatedAt(LocalDateTime.now(FIXED_CLOCK));
        Poster posterAfterOverwrite = posterUpdatedAt(LocalDateTime.now(FIXED_CLOCK).plusMinutes(1));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(Optional.of(posterAtModeration))
                .thenReturn(Optional.of(posterAfterOverwrite));
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenReturn(new PosterModerationOutcome(ModerationResult.PASS, List.of()));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    /*
     * 검수 시점에는 포스터가 있었지만, schedule()이 캠페인 잠금을 잡은 뒤 precondition이 다시
     * 조회할 때는 포스터가 사라진 상황을 흉내낸다. verifyPosterUnchanged()도 POSTER_NOT_SAVED를
     * 그대로 던져야 한다.
     */
    @Test
    @DisplayName("검수 뒤 포스터가 사라지면 POSTER_NOT_SAVED로 거부하고 전이하지 않는다")
    void 검수_뒤_포스터가_사라지면_거부한다() {
        active();
        Campaign campaign = draftCampaign(OWNER_ID);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        Poster posterAtModeration = posterUpdatedAt(LocalDateTime.now(FIXED_CLOCK));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(Optional.of(posterAtModeration))
                .thenReturn(Optional.empty());
        when(posterModerationService.moderate(CAMPAIGN_ID))
                .thenReturn(new PosterModerationOutcome(ModerationResult.PASS, List.of()));

        assertThatThrownBy(() -> service.activate(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_NOT_SAVED));
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    private void active() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
    }

    private Campaign draftCampaignWith(Long dailyBudget, LocalDate startDate, LocalDate endDate) {
        Campaign campaign = Campaign.create(
                OWNER_ID, 20L,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                dailyBudget, startDate, endDate,
                LocalDateTime.now(FIXED_CLOCK));
        setId(campaign, CAMPAIGN_ID);
        return campaign;
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
