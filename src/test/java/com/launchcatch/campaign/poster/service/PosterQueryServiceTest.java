package com.launchcatch.campaign.poster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        assertThat(response.html()).isEqualTo("<div>html</div>");
    }

    @Test
    @DisplayName("공개된 포스터는 두 번째 조회부터 DB 를 읽지 않고 같은 HTML 을 돌려준다(#940)")
    void 공개된_포스터는_두_번째부터_캐시에서_돌려준다() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.ACTIVE)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster()));

        PosterResponse first = service.findForMember(CAMPAIGN_ID);
        PosterResponse second = service.findForMember(CAMPAIGN_ID);

        assertThat(second).isEqualTo(first);
        verify(campaignRepository, times(1)).findById(CAMPAIGN_ID);
        verify(posterRepository, times(1)).findByCampaignId(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("캠페인마다 따로 캐시한다. 다른 캠페인의 조회는 DB 를 읽는다(#940)")
    void 캐시는_캠페인마다_따로_둔다() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        Long otherCampaignId = 502L;
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.ACTIVE)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster()));
        when(campaignRepository.findById(otherCampaignId)).thenReturn(Optional.of(campaign(CampaignStatus.ACTIVE)));
        when(posterRepository.findByCampaignId(otherCampaignId)).thenReturn(Optional.of(
                Poster.create(otherCampaignId, TEMPLATE_ID, "다른 가게 - 가을 신메뉴", "{}", "<div>other</div>", "k")));

        service.findForMember(CAMPAIGN_ID);
        PosterResponse other = service.findForMember(otherCampaignId);

        assertThat(other.html()).isEqualTo("<div>other</div>");
        verify(campaignRepository).findById(otherCampaignId);
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"DRAFT", "SCHEDULED"})
    @DisplayName("비공개 캠페인은 캐시하지 않아 공개된 뒤의 조회에 바로 반영된다(#940)")
    void 비공개는_캐시하지_않는다(CampaignStatus status) {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(Optional.of(campaign(status)), Optional.of(campaign(CampaignStatus.ACTIVE)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster()));

        assertThatThrownBy(() -> service.findForMember(CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_UNAVAILABLE));
        PosterResponse afterPublic = service.findForMember(CAMPAIGN_ID);

        assertThat(afterPublic.html()).isEqualTo("<div>html</div>");
        verify(campaignRepository, times(2)).findById(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("공개 캠페인에 포스터가 없는 결과도 캐시하지 않아 포스터가 생기면 바로 반영된다(#940)")
    void 포스터_없음은_캐시하지_않는다() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.ACTIVE)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.empty(), Optional.of(poster()));

        assertThatThrownBy(() -> service.findForMember(CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_UNAVAILABLE));
        PosterResponse afterCreated = service.findForMember(CAMPAIGN_ID);

        assertThat(afterCreated.html()).isEqualTo("<div>html</div>");
        verify(posterRepository, times(2)).findByCampaignId(CAMPAIGN_ID);
    }

    /*
     * 비공개 상태에서도 포스터 조회(posterRepository.findByCampaignId)를 그대로 호출해야
     * 한다. 캠페인 조회에서만 끝내면 비공개·존재하지 않는 캠페인은 조회 1번, 포스터만 없는
     * 공개 캠페인은 조회 2번이 걸려 응답 시간으로 공개 여부를 가를 수 있다(#495 자체 리뷰로
     * 찾음).
     */
    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"DRAFT", "SCHEDULED"})
    @DisplayName("DRAFT, SCHEDULED 캠페인은 사용자에게 비공개다")
    void 사용자_조회_비공개_상태(CampaignStatus status) {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(status)));

        assertThatThrownBy(() -> service.findForMember(CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_UNAVAILABLE));
        verify(posterRepository).findByCampaignId(CAMPAIGN_ID);
    }

    @Test
    @DisplayName("캠페인이 없으면 사용자 조회를 같은 오류로 거부한다")
    void 사용자_조회_캠페인_없음() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findForMember(CAMPAIGN_ID))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_UNAVAILABLE));
        verify(posterRepository).findByCampaignId(CAMPAIGN_ID);
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

    @Test
    @DisplayName("점주 응답에 슬롯별 현재 값(slots)이 들어간다")
    void 점주_조회에_슬롯_값이_들어간다() {
        PosterQueryService service = service(campaignRepository, posterRepository);
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign(CampaignStatus.DRAFT)));
        Poster withSlots = Poster.create(CAMPAIGN_ID, TEMPLATE_ID, "분식집 - 가을 신메뉴",
                "{\"eventName\":{\"type\":\"text\",\"value\":\"분식집\",\"fontSizePx\":30},"
                        + "\"discount\":{\"type\":\"text\",\"value\":\"1000원 할인 오늘만\",\"baseText\":\"1000원 할인\","
                        + "\"suffix\":\"오늘만\"},"
                        + "\"period\":{\"type\":\"text\",\"value\":\"10.1~10.31\"}}",
                "<p data-slot=\"discount\" style=\"font-size:18px\">1000원 할인 오늘만</p>", "campaigns/501/poster/x.jpg");
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(withSlots));

        PosterCreateResponse response = service.findForOwner(OWNER_ID, CAMPAIGN_ID);

        assertThat(response.slots().eventName().text()).isEqualTo("분식집");
        assertThat(response.slots().eventName().fontSizePx()).isEqualTo(30);
        assertThat(response.slots().discount().baseText()).isEqualTo("1000원 할인");
        assertThat(response.slots().discount().suffix()).isEqualTo("오늘만");
        assertThat(response.slots().discount().fontSizePx()).isEqualTo(18);
        assertThat(response.slots().period().text()).isEqualTo("10.1~10.31");
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
