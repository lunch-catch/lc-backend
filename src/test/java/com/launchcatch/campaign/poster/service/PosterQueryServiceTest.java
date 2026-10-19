package com.launchcatch.campaign.poster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.poster.dto.PosterCreateResponse;
import com.launchcatch.campaign.poster.dto.PosterResponse;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PosterQueryServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_OWNER_ID = 9L;
    private static final Long STORE_ID = 20L;
    private static final Long CAMPAIGN_ID = 501L;
    private static final Long TEMPLATE_ID = 1L;

    @Mock private CampaignRepository campaignRepository;
    @Mock private PosterRepository posterRepository;

    private PosterQueryService service(CampaignRepository campaignRepository, PosterRepository posterRepository) {
        return new PosterQueryService(campaignRepository, posterRepository);
    }

    @Test
    @DisplayName("본인 소유 캠페인의 포스터를 돌려준다")
    void 점주_조회_성공() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster()));

        PosterCreateResponse response = service.findForOwner(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(response.html()).isEqualTo("<div>html</div>");
    }

    /** 점주용 조회는 상태를 보지 않는다. DRAFT도 성공한다. */
    @ParameterizedTest
    @EnumSource(CampaignStatus.class)
    void 점주_조회는_캠페인_상태와_무관하다(CampaignStatus status) {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(status)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster()));

        assertThat(service.findForOwner(OWNER_ID, CAMPAIGN_ID)).isNotNull();
    }

    @Test
    @DisplayName("캠페인이 없거나 본인 소유가 아니면 점주 조회를 거부한다")
    void 점주_조회_소유권_실패() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));

        assertThatThrownBy(() -> service.findForOwner(OTHER_OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("포스터가 없으면 점주 조회를 거부한다")
    void 점주_조회_포스터_없음() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findForOwner(OWNER_ID, CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_NOT_SAVED));
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"ACTIVE", "PAUSED", "ENDED"})
    @DisplayName("ACTIVE, PAUSED, ENDED 캠페인의 포스터는 사용자에게 공개된다")
    void 사용자_조회_성공(CampaignStatus status) {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(status)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster()));

        PosterResponse response = service.findForMember(CAMPAIGN_ID);

        assertThat(response.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.html()).isEqualTo("<div>html</div>");
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"DRAFT", "SCHEDULED"})
    @DisplayName("DRAFT, SCHEDULED 캠페인은 사용자에게 비공개다")
    void 사용자_조회_비공개_상태(CampaignStatus status) {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(status)));

        assertThatThrownBy(() -> service.findForMember(CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_UNAVAILABLE));
    }

    @Test
    @DisplayName("캠페인이 없으면 사용자 조회를 같은 오류로 거부한다")
    void 사용자_조회_캠페인_없음() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findForMember(CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_UNAVAILABLE));
    }

    @Test
    @DisplayName("공개 상태인 캠페인이라도 포스터가 없으면 같은 오류로 거부한다")
    void 사용자_조회_포스터_없음() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.ACTIVE)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findForMember(CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_UNAVAILABLE));
    }

    private Campaign campaign(CampaignStatus status) {
        Campaign campaign = Campaign.create(
                OWNER_ID, STORE_ID,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.now());
        setStatus(campaign, status);
        return campaign;
    }

    private Poster poster() {
        return Poster.create(CAMPAIGN_ID, TEMPLATE_ID, "분식집 - 가을 신메뉴", "{}", "<div>html</div>", "campaigns/501/poster/x.jpg");
    }

    private void setStatus(Campaign campaign, CampaignStatus status) {
        if (status == CampaignStatus.DRAFT) {
            return;
        }
        try {
            Field field = Campaign.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(campaign, status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
