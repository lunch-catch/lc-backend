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
import com.launchcatch.campaign.poster.dto.PosterUpdateRequest;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.repository.CampaignRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PosterEditServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long OTHER_OWNER_ID = 9L;
    private static final Long STORE_ID = 20L;
    private static final Long CAMPAIGN_ID = 501L;
    private static final Long TEMPLATE_ID = 1L;

    private static final String HTML = "<div style=\"color:red\">"
            + "<h1 data-slot=\"eventName\" style=\"font-size:20px;color:blue\">분식집</h1>"
            + "<p data-slot=\"discount\">1000원 할인</p>"
            + "<p data-slot=\"period\">10.1~10.31</p>"
            + "<img data-slot=\"image\" src=\"x.jpg\">"
            + "</div>";
    private static final String SLOT_VALUES = "{\"eventName\":{\"type\":\"text\",\"value\":\"분식집\"},"
            + "\"discount\":{\"type\":\"text\",\"value\":\"1000원 할인\"},"
            + "\"period\":{\"type\":\"text\",\"value\":\"10.1~10.31\"},"
            + "\"image\":{\"type\":\"image\",\"value\":\"x.jpg\"}}";

    @Mock private CampaignRepository campaignRepository;
    @Mock private PosterRepository posterRepository;
    @Mock private OwnerStatusQuery ownerStatusQuery;

    private PosterEditService service;

    @BeforeEach
    void setUp() {
        service = new PosterEditService(campaignRepository, posterRepository, ownerStatusQuery);
    }

    @Test
    @DisplayName("가게명 텍스트와 글자 크기를 고치면 HTML 과 slot_values 가 함께 바뀐다")
    void 가게명을_고친다() {
        Poster poster = givenPoster(SLOT_VALUES, HTML);

        PosterCreateResponse response = service.update(OWNER_ID, CAMPAIGN_ID,
                new PosterUpdateRequest(new PosterUpdateRequest.EventName("  점심 특가  ", 32), null));

        assertThat(poster.getHtmlContent()).contains(">점심 특가</h1>").contains("font-size:32px")
                .doesNotContain("font-size:20px").contains("color:blue").contains("1000원 할인");
        assertThat(poster.getSlotValues()).contains("\"baseText\":\"분식집\"").contains("\"fontSizePx\":32");
        assertThat(response.html()).isEqualTo(poster.getHtmlContent());
        assertThat(response.slots().eventName().text()).isEqualTo("점심 특가");
        assertThat(response.slots().eventName().fontSizePx()).isEqualTo(32);
    }

    @Test
    @DisplayName("가게명은 글자 크기만 보내면 텍스트를 그대로 둔다")
    void 가게명_글자_크기만_고친다() {
        Poster poster = givenPoster(SLOT_VALUES, HTML);

        service.update(OWNER_ID, CAMPAIGN_ID,
                new PosterUpdateRequest(new PosterUpdateRequest.EventName(null, 40), null));

        assertThat(poster.getHtmlContent()).contains(">분식집</h1>").contains("font-size:40px");
    }

    @Test
    @DisplayName("할인 추가 텍스트는 기본 문구 뒤에 공백 하나로 붙고 빈 문자열이면 지워진다")
    void 할인_추가_텍스트를_붙이고_지운다() {
        Poster poster = givenPoster(SLOT_VALUES, HTML);

        service.update(OWNER_ID, CAMPAIGN_ID,
                new PosterUpdateRequest(null, new PosterUpdateRequest.Discount("(1인 1개)", 26)));

        assertThat(poster.getHtmlContent()).contains(">1000원 할인 (1인 1개)</p>").contains("font-size:26px");
        assertThat(poster.getSlotValues()).contains("\"baseText\":\"1000원 할인\"").contains("\"suffix\":\"(1인 1개)\"");

        PosterCreateResponse cleared = service.update(OWNER_ID, CAMPAIGN_ID,
                new PosterUpdateRequest(null, new PosterUpdateRequest.Discount("", null)));

        assertThat(poster.getHtmlContent()).contains(">1000원 할인</p>").contains("font-size:26px");
        assertThat(cleared.slots().discount().suffix()).isEmpty();
        assertThat(cleared.slots().discount().baseText()).isEqualTo("1000원 할인");
        assertThat(cleared.slots().discount().fontSizePx()).isEqualTo(26);
    }

    @Test
    @DisplayName("할인은 글자 크기만 보내면 텍스트를 그대로 둔다")
    void 할인_글자_크기만_고친다() {
        Poster poster = givenPoster(SLOT_VALUES, HTML);

        service.update(OWNER_ID, CAMPAIGN_ID,
                new PosterUpdateRequest(null, new PosterUpdateRequest.Discount(null, 14)));

        assertThat(poster.getHtmlContent()).contains(">1000원 할인</p>").contains("font-size:14px");
    }

    @Test
    @DisplayName("보낸 필드가 없으면 포스터를 바꾸지 않는다")
    void 빈_요청은_바꾸지_않는다() {
        Poster poster = givenPoster(SLOT_VALUES, HTML);

        PosterCreateResponse response = service.update(OWNER_ID, CAMPAIGN_ID, new PosterUpdateRequest(null, null));

        assertThat(poster.getHtmlContent()).isEqualTo(HTML);
        assertThat(poster.getSlotValues()).isEqualTo(SLOT_VALUES);
        assertThat(response.slots().eventName().fontSizePx()).isEqualTo(20);
    }

    @Test
    @DisplayName("가게명과 할인을 한 번에 고칠 수 있다")
    void 둘을_한_번에_고친다() {
        Poster poster = givenPoster(SLOT_VALUES, HTML);

        service.update(OWNER_ID, CAMPAIGN_ID, new PosterUpdateRequest(
                new PosterUpdateRequest.EventName("새 가게", 30), new PosterUpdateRequest.Discount("오늘만", 20)));

        assertThat(poster.getHtmlContent()).contains(">새 가게</h1>").contains(">1000원 할인 오늘만</p>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "가\n나", "가\t나", "가\u200B나", "가\u2028나"})
    @DisplayName("가게명이 비었거나 줄바꿈과 보이지 않는 문자가 있으면 POSTER-026 이다")
    void 가게명_텍스트가_틀리면_거절한다(String text) {
        givenPoster(SLOT_VALUES, HTML);

        assertError(new PosterUpdateRequest(new PosterUpdateRequest.EventName(text, null), null),
                PosterErrorCode.POSTER_TEXT_INVALID);
    }

    @Test
    @DisplayName("가게명이 30자를 넘으면 POSTER-026 이고 30자는 통과한다(코드 포인트로 센다)")
    void 가게명_길이를_코드_포인트로_센다() {
        Poster poster = givenPoster(SLOT_VALUES, HTML);

        assertError(new PosterUpdateRequest(new PosterUpdateRequest.EventName("가".repeat(31), null), null),
                PosterErrorCode.POSTER_TEXT_INVALID);
        service.update(OWNER_ID, CAMPAIGN_ID,
                new PosterUpdateRequest(new PosterUpdateRequest.EventName("😀".repeat(30), null), null));

        assertThat(poster.getHtmlContent()).contains("😀".repeat(30));
    }

    @Test
    @DisplayName("할인 추가 텍스트가 20자를 넘거나 줄바꿈이 있으면 POSTER-026 이다")
    void 할인_추가_텍스트가_틀리면_거절한다() {
        givenPoster(SLOT_VALUES, HTML);

        assertError(new PosterUpdateRequest(null, new PosterUpdateRequest.Discount("가".repeat(21), null)),
                PosterErrorCode.POSTER_TEXT_INVALID);
        assertError(new PosterUpdateRequest(null, new PosterUpdateRequest.Discount("가\n나", null)),
                PosterErrorCode.POSTER_TEXT_INVALID);
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 49})
    @DisplayName("가게명 글자 크기가 16~48 밖이면 POSTER-027 이다")
    void 가게명_글자_크기_범위를_벗어나면_거절한다(int fontSizePx) {
        givenPoster(SLOT_VALUES, HTML);

        assertError(new PosterUpdateRequest(new PosterUpdateRequest.EventName(null, fontSizePx), null),
                PosterErrorCode.POSTER_FONT_SIZE_OUT_OF_RANGE);
    }

    @ParameterizedTest
    @ValueSource(ints = {13, 41})
    @DisplayName("할인 글자 크기가 14~40 밖이면 POSTER-027 이다")
    void 할인_글자_크기_범위를_벗어나면_거절한다(int fontSizePx) {
        givenPoster(SLOT_VALUES, HTML);

        assertError(new PosterUpdateRequest(null, new PosterUpdateRequest.Discount(null, fontSizePx)),
                PosterErrorCode.POSTER_FONT_SIZE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("저장된 slot_values 에 슬롯이 없으면 POSTER-001 이다")
    void slot_values에_슬롯이_없으면_거절한다() {
        givenPoster("{}", HTML);

        assertError(new PosterUpdateRequest(new PosterUpdateRequest.EventName("가", null), null),
                PosterErrorCode.SLOT_CONTRACT_VIOLATION);
    }

    @Test
    @DisplayName("저장된 HTML 의 슬롯이 없거나 자식 요소가 있으면 POSTER-001 이다")
    void HTML의_슬롯이_맞지_않으면_거절한다() {
        givenPoster(SLOT_VALUES, HTML.replace(">분식집<", "><b>분식집</b><"));
        assertError(new PosterUpdateRequest(new PosterUpdateRequest.EventName("가", null), null),
                PosterErrorCode.SLOT_CONTRACT_VIOLATION);

        givenPoster(SLOT_VALUES, "<div></div>");
        assertError(new PosterUpdateRequest(null, new PosterUpdateRequest.Discount("가", null)),
                PosterErrorCode.SLOT_CONTRACT_VIOLATION);
    }

    @Test
    @DisplayName("점주 계정이 ACTIVE 가 아니면 거절한다")
    void 활성_점주가_아니면_거절한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.update(OWNER_ID, CAMPAIGN_ID, new PosterUpdateRequest(null, null)))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
    }

    @Test
    @DisplayName("다른 점주의 캠페인이거나 없으면 CAMPAIGN-005 이다")
    void 소유하지_않은_캠페인이면_거절한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID))
                .thenReturn(Optional.of(campaign(OTHER_OWNER_ID, CampaignStatus.DRAFT)));

        assertThatThrownBy(() -> service.update(OWNER_ID, CAMPAIGN_ID, new PosterUpdateRequest(null, null)))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("DRAFT 가 아닌 캠페인이면 CAMPAIGN-006 이다")
    void DRAFT가_아니면_거절한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID))
                .thenReturn(Optional.of(campaign(OWNER_ID, CampaignStatus.SCHEDULED)));

        assertThatThrownBy(() -> service.update(OWNER_ID, CAMPAIGN_ID, new PosterUpdateRequest(null, null)))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("저장된 포스터가 없으면 POSTER-020 이다")
    void 포스터가_없으면_거절한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID))
                .thenReturn(Optional.of(campaign(OWNER_ID, CampaignStatus.DRAFT)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(OWNER_ID, CAMPAIGN_ID, new PosterUpdateRequest(null, null)))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_NOT_SAVED));
    }

    private Poster givenPoster(String slotValues, String html) {
        Poster poster = Poster.create(CAMPAIGN_ID, TEMPLATE_ID, "분식집 - 가을 신메뉴", slotValues, html,
                "campaigns/501/poster/x.jpg");
        stub(campaign(OWNER_ID, CampaignStatus.DRAFT), poster);
        return poster;
    }

    private void stub(Campaign campaign, Poster poster) {
        org.mockito.Mockito.lenient().when(ownerStatusQuery.findCurrentStatus(OWNER_ID))
                .thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
        org.mockito.Mockito.lenient().when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID))
                .thenReturn(Optional.of(campaign));
        org.mockito.Mockito.lenient().when(posterRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(Optional.of(poster));
    }

    private void assertError(PosterUpdateRequest request, PosterErrorCode expected) {
        assertThatThrownBy(() -> service.update(OWNER_ID, CAMPAIGN_ID, request))
                .isInstanceOfSatisfying(CampaignException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private Campaign campaign(Long ownerId, CampaignStatus status) {
        Campaign campaign = Campaign.create(
                ownerId, STORE_ID,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.now());
        if (status != CampaignStatus.DRAFT) {
            try {
                Field field = Campaign.class.getDeclaredField("status");
                field.setAccessible(true);
                field.set(campaign, status);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return campaign;
    }
}
