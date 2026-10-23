package com.launchcatch.campaign.poster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.poster.dto.PosterCreateResponse;
import com.launchcatch.campaign.poster.dto.PosterImagePrepareUploadResponse;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreMenuQueryResult;
import com.launchcatch.store.contract.StoreQueryService;
import java.lang.reflect.Field;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class PosterUploadServiceTest {

    private static final Long OWNER_ID = 7L;
    private static final Long STORE_ID = 20L;
    private static final Long CAMPAIGN_ID = 501L;
    private static final Long TEMPLATE_ID = 1L;
    private static final String OBJECT_KEY = "campaigns/501/poster/upload-1.jpg";
    // 광고 표시(adLabel)가 들어 있는 옛 템플릿이다. 포스터에는 광고 표시를 넣지 않는다(#628).
    private static final String TEMPLATE_HTML = ""
            + "<div data-slot=\"eventName\">old</div>"
            + "<div data-slot=\"discount\">old</div>"
            + "<div data-slot=\"period\">old</div>"
            + "<span data-slot=\"adLabel\">광고</span>"
            + "<img data-slot=\"image\" src=\"old\">";

    @Mock private CampaignRepository campaignRepository;
    @Mock private TemplateRepository templateRepository;
    @Mock private PosterRepository posterRepository;
    @Mock private StoreQueryService storeQueryService;
    @Mock private OwnerStatusQuery ownerStatusQuery;
    @Mock private S3Presigner s3Presigner;
    @Mock private S3Client s3Client;
    @Mock private PresignedPutObjectRequest presignedPutObjectRequest;
    @Mock private TransactionTemplate transactionTemplate;

    private PosterUploadService service;

    private final List<ListAppender<ILoggingEvent>> attachedAppenders = new java.util.ArrayList<>();

    private ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(PosterUploadService.class)).addAppender(appender);
        attachedAppenders.add(appender);
        return appender;
    }

    @AfterEach
    void detachLogAppenders() {
        Logger logger = (Logger) LoggerFactory.getLogger(PosterUploadService.class);
        attachedAppenders.forEach(logger::detachAppender);
        attachedAppenders.clear();
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = new PosterUploadService(
                campaignRepository, templateRepository, posterRepository, storeQueryService, ownerStatusQuery,
                s3Presigner, s3Client, transactionTemplate, "https://cdn.example.com");
        try {
            Field bucketField = PosterUploadService.class.getDeclaredField("mediaBucket");
            bucketField.setAccessible(true);
            bucketField.set(service, "test-media-bucket");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        lenient().when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));
    }

    @Test
    @DisplayName("허용하지 않는 파일 형식이면 거부한다")
    void 허용하지_않는_형식이면_거부한다() {
        assertThatThrownBy(() -> service.prepareImageUpload(OWNER_ID, CAMPAIGN_ID, "image/gif", 1000L))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_IMAGE_CONTENT_TYPE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("10MB를 넘는 파일 크기면 거부한다")
    void 파일_크기_상한을_넘으면_거부한다() {
        assertThatThrownBy(() -> service.prepareImageUpload(OWNER_ID, CAMPAIGN_ID, "image/jpeg", 10_485_761L))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_IMAGE_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("ACTIVE가 아닌 점주는 업로드 준비를 거부한다")
    void ACTIVE가_아닌_점주는_준비를_거부한다() {
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> service.prepareImageUpload(OWNER_ID, CAMPAIGN_ID, "image/jpeg", 1000L))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.OWNER_NOT_ACTIVE));
    }

    @Test
    @DisplayName("캠페인이 없거나 본인 소유가 아니면 업로드 준비를 거부한다")
    void 캠페인이_없으면_준비를_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.prepareImageUpload(OWNER_ID, CAMPAIGN_ID, "image/jpeg", 1000L))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("캠페인이 DRAFT가 아니면 업로드 준비를 거부한다")
    void DRAFT가_아니면_준비를_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.ACTIVE)));

        assertThatThrownBy(() -> service.prepareImageUpload(OWNER_ID, CAMPAIGN_ID, "image/jpeg", 1000L))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("집행 기간이 비어 있어도 업로드 준비가 통과한다(#763, 등록 순서가 포스터 다음에 집행 기간이다)")
    void 집행_기간이_없어도_준비가_통과한다() throws Exception {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaignWithoutPeriod()));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url()).thenReturn(new URL("https://example.com/presigned"));

        PosterImagePrepareUploadResponse response =
                service.prepareImageUpload(OWNER_ID, CAMPAIGN_ID, "image/jpeg", 1000L);

        assertThat(response.uploadUrl()).isEqualTo("https://example.com/presigned");
    }

    @Test
    @DisplayName("정상이면 objectKey와 presigned URL을 돌려준다")
    void 정상이면_업로드_URL을_돌려준다() throws Exception {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);
        when(presignedPutObjectRequest.url()).thenReturn(new URL("https://example.com/presigned"));

        PosterImagePrepareUploadResponse response =
                service.prepareImageUpload(OWNER_ID, CAMPAIGN_ID, "image/jpeg", 1000L);

        assertThat(response.uploadUrl()).isEqualTo("https://example.com/presigned");
        assertThat(response.objectKey()).startsWith("campaigns/" + CAMPAIGN_ID + "/poster/").endsWith(".jpg");
    }

    @Test
    @DisplayName("집행 기간이 비어 있어도 쿠폰 사용 시간으로 기간 문구를 만들어 포스터를 만든다(#763)")
    void 집행_기간이_없어도_포스터를_만든다() {
        Campaign campaign = draftCampaignWithoutPeriod();
        Template template = publishedActiveTemplate();
        givenHappyPath(campaign, template);

        PosterCreateResponse response = service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY);

        assertThat(response.html()).contains("매일 12:00 ~ 13:30");
    }

    @Test
    @DisplayName("템플릿이 없으면 생성을 거부한다")
    void 템플릿이_없으면_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("템플릿이 게시되지 않았으면 생성을 거부한다")
    void 게시되지_않은_템플릿이면_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(Template.createDraft("가을 신메뉴")));

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_USABLE_FOR_POSTER));
    }

    @Test
    @DisplayName("템플릿이 게시됐지만 비활성이면 생성을 거부한다")
    void 비활성_템플릿이면_거부한다() {
        Template inactive = Template.createDraft("가을 신메뉴");
        inactive.addDraftVersion(1L, LocalDateTime.now(), "요청", "req-1", TEMPLATE_HTML, 1);
        inactive.publish(TEMPLATE_HTML, LocalDateTime.now(), 1);

        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_USABLE_FOR_POSTER));
    }

    @Test
    @DisplayName("다른 캠페인이나 다른 경로를 가리키는 objectKey면 생성을 거부한다")
    void 다른_캠페인의_objectKey면_거부한다() {
        assertThatThrownBy(() ->
                service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, "campaigns/999/poster/other.jpg"))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_IMAGE_OBJECT_KEY_INVALID));
    }

    @Test
    @DisplayName("실제로 업로드되지 않았으면 생성을 거부한다")
    void 업로드되지_않았으면_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(publishedActiveTemplate()));
        when(storeQueryService.findAllById(List.of(STORE_ID)))
                .thenReturn(List.of(new StoreInfo(STORE_ID, "분식집", "store.jpg", 37.5, 127.0)));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().message("not found").statusCode(404).build());

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_IMAGE_NOT_UPLOADED));
    }

    @Test
    @DisplayName("업로드된 파일이 허용되지 않는 형식이면 생성을 거부한다")
    void 업로드된_파일_형식이_허용되지_않으면_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(publishedActiveTemplate()));
        when(storeQueryService.findAllById(List.of(STORE_ID)))
                .thenReturn(List.of(new StoreInfo(STORE_ID, "분식집", "store.jpg", 37.5, 127.0)));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentType("application/pdf").contentLength(1000L).build());

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_IMAGE_CONTENT_TYPE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("업로드된 파일이 10MB를 넘으면 생성을 거부한다")
    void 업로드된_파일이_너무_크면_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(publishedActiveTemplate()));
        when(storeQueryService.findAllById(List.of(STORE_ID)))
                .thenReturn(List.of(new StoreInfo(STORE_ID, "분식집", "store.jpg", 37.5, 127.0)));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentType("image/jpeg").contentLength(10_485_761L).build());

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.POSTER_IMAGE_FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("가게를 찾지 못하면 내부 오류로 거부한다")
    void 가게를_찾지_못하면_내부_오류로_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(publishedActiveTemplate()));
        when(storeQueryService.findAllById(List.of(STORE_ID))).thenReturn(List.of());

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("메뉴를 찾지 못하면 내부 오류로 거부한다")
    void 메뉴를_찾지_못하면_내부_오류로_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(menuDiscountCampaign()));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(publishedActiveTemplate()));
        when(storeQueryService.findAllById(List.of(STORE_ID)))
                .thenReturn(List.of(new StoreInfo(STORE_ID, "분식집", "store.jpg", 37.5, 127.0)));
        when(storeQueryService.findMenusByIdIn(List.of(42L))).thenReturn(new StoreMenuQueryResult(List.of(), List.of()));
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("event=POSTER_MENU_NOT_FOUND"))
                .noneMatch(message -> message.contains("event=POSTER_MENU_IMAGE_INVALID"));
    }

    @Test
    @DisplayName("메뉴의 이미지 데이터 오류는 메뉴 없음과 다른 이벤트로 로그를 남기고 같은 내부 오류로 거부한다(#851)")
    void 메뉴의_데이터_오류가_있으면_내부_오류로_포스터_생성을_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(menuDiscountCampaign()));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(publishedActiveTemplate()));
        when(storeQueryService.findAllById(List.of(STORE_ID)))
                .thenReturn(List.of(new StoreInfo(STORE_ID, "분식집", "store.jpg", 37.5, 127.0)));
        when(storeQueryService.findMenusByIdIn(List.of(42L)))
                .thenReturn(new StoreMenuQueryResult(List.of(), List.of(42L)));
        ListAppender<ILoggingEvent> appender = attachLogAppender();

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR));

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("event=POSTER_MENU_IMAGE_INVALID"))
                .noneMatch(message -> message.contains("event=POSTER_MENU_NOT_FOUND"));
    }

    @Test
    @DisplayName("headObject 확인 사이에 캠페인이 사라지거나 본인 소유가 아니게 되면 생성을 거부한다")
    void 잠금_재확인에서_캠페인을_못_찾으면_거부한다() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(draftCampaign(CampaignStatus.DRAFT)));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.empty());
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(publishedActiveTemplate()));
        when(storeQueryService.findAllById(List.of(STORE_ID)))
                .thenReturn(List.of(new StoreInfo(STORE_ID, "분식집", "store.jpg", 37.5, 127.0)));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentType("image/jpeg").contentLength(1000L).build());

        assertThatThrownBy(() -> service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
    }

    @Test
    @DisplayName("전체 할인이면 가게명, 할인 문구, 기간, 이미지를 슬롯에 채워 포스터를 만든다")
    void 전체_할인이면_포스터를_만든다() {
        Campaign campaign = draftCampaign(CampaignStatus.DRAFT);
        Template template = publishedActiveTemplate();
        givenHappyPath(campaign, template);

        PosterCreateResponse response = service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY);

        assertThat(response.title()).isEqualTo("분식집 - 가을 신메뉴");
        assertThat(response.html()).contains("분식집")
                .contains("전체 메뉴 10원 할인")
                .contains("매일 12:00 ~ 13:30")
                .doesNotContain("adLabel")
                .doesNotContain("광고")
                .contains("https://cdn.example.com/" + OBJECT_KEY);
    }

    @Test
    @DisplayName("스타일이 없는 이미지 슬롯에도 크기 보정 스타일을 넣는다(#731)")
    void 스타일이_없으면_크기_보정_스타일을_넣는다() {
        Campaign campaign = draftCampaign(CampaignStatus.DRAFT);
        Template template = publishedActiveTemplate();
        givenHappyPath(campaign, template);

        PosterCreateResponse response = service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY);

        assertThat(imageStyle(response.html()))
                .contains("width:100%").contains("height:100%").contains("min-height:0").contains("object-fit:cover");
    }

    @Test
    @DisplayName("이미지 슬롯의 기존 스타일은 유지하고 같은 속성은 서버 값으로 한 번만 덮어쓴다(#731)")
    void 기존_스타일은_유지하고_같은_속성은_덮어쓴다() {
        Campaign campaign = draftCampaign(CampaignStatus.DRAFT);
        Template template = publishedActiveTemplate(TEMPLATE_HTML.replace(
                "<img data-slot=\"image\" src=\"old\">",
                "<img data-slot=\"image\" src=\"old\" style=\"border-radius:16px;WIDTH:50%;box-shadow:0 6px 16px rgba(0,0,0,0.25)\">"));
        givenHappyPath(campaign, template);

        PosterCreateResponse response = service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY);

        String style = imageStyle(response.html());
        assertThat(style).contains("border-radius:16px").contains("box-shadow:0 6px 16px rgba(0,0,0,0.25)")
                .contains("width:100%").doesNotContain("50%");
        assertThat(style.split("width:", -1)).hasSize(2);
    }

    @Test
    @DisplayName("메뉴 할인이면 메뉴명을 넣어 할인 문구를 만든다")
    void 메뉴_할인이면_메뉴명을_넣는다() {
        Campaign campaign = menuDiscountCampaign();
        Template template = publishedActiveTemplate();
        givenHappyPath(campaign, template);
        when(storeQueryService.findMenusByIdIn(List.of(42L)))
                .thenReturn(new StoreMenuQueryResult(List.of(new StoreMenuInfo(42L, STORE_ID, "menu.jpg", "제육볶음", 9000L)), List.of()));

        PosterCreateResponse response = service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY);

        assertThat(response.html()).contains("제육볶음 3000원 할인");
    }

    /*
     * ALL(전체 메뉴)은 AMOUNT만 허용해 "원" 문구만 남았다(#431). MENU(특정 메뉴)는
     * PERCENT를 여전히 허용해, 할인 문구의 "%" 분기(formatDiscount)는 이 경로로만 확인된다.
     */
    @Test
    @DisplayName("메뉴 할인이 PERCENT면 할인율 문구를 만든다")
    void 메뉴_할인이_PERCENT면_할인율_문구를_만든다() {
        Campaign campaign = menuPercentDiscountCampaign();
        Template template = publishedActiveTemplate();
        givenHappyPath(campaign, template);
        when(storeQueryService.findMenusByIdIn(List.of(42L)))
                .thenReturn(new StoreMenuQueryResult(List.of(new StoreMenuInfo(42L, STORE_ID, "menu.jpg", "제육볶음", 9000L)), List.of()));

        PosterCreateResponse response = service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY);

        assertThat(response.html()).contains("제육볶음 15% 할인");
    }

    @Test
    @DisplayName("이미 포스터가 있으면 새로 만들지 않고 덮어쓴다")
    void 이미_포스터가_있으면_덮어쓴다() throws Exception {
        Campaign campaign = draftCampaign(CampaignStatus.DRAFT);
        Template template = publishedActiveTemplate();
        givenHappyPath(campaign, template);
        Poster existing = Poster.create(CAMPAIGN_ID, 999L, "old title", "{}", "old html", "old-key");
        setId(existing, 10L);
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(existing));

        PosterCreateResponse response = service.createOrOverwritePoster(OWNER_ID, CAMPAIGN_ID, TEMPLATE_ID, OBJECT_KEY);

        assertThat(response.posterId()).isEqualTo(10L);
        assertThat(existing.getTemplateId()).isEqualTo(TEMPLATE_ID);
        assertThat(existing.getImageObjectKey()).isEqualTo(OBJECT_KEY);
        verify(posterRepository, never()).save(any(Poster.class));
    }

    private void givenHappyPath(Campaign campaign, Template template) {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentType("image/jpeg").contentLength(1000L).build());
        when(storeQueryService.findAllById(List.of(STORE_ID)))
                .thenReturn(List.of(new StoreInfo(STORE_ID, "분식집", "store.jpg", 37.5, 127.0)));
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.empty());
        lenient().when(posterRepository.save(any(Poster.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private String imageStyle(String html) {
        return org.jsoup.Jsoup.parseBodyFragment(html).selectFirst("[data-slot=image]").attr("style");
    }

    private Template publishedActiveTemplate() {
        return publishedActiveTemplate(TEMPLATE_HTML);
    }

    private Template publishedActiveTemplate(String html) {
        Template template = Template.createDraft("가을 신메뉴");
        template.addDraftVersion(1L, LocalDateTime.now(), "요청", "req-1", html, 1);
        template.publish(html, LocalDateTime.now(), 1);
        template.changeActive(true, 1L, LocalDateTime.now());
        return template;
    }

    private Campaign draftCampaign(CampaignStatus status) {
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

    // 캠페인 등록 1단계만 끝낸 DRAFT는 집행 기간이 비어 있을 수 있다.
    private Campaign draftCampaignWithoutPeriod() {
        return Campaign.create(
                OWNER_ID, STORE_ID,
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now());
    }

    private Campaign menuDiscountCampaign() {
        Campaign campaign = Campaign.create(
                OWNER_ID, STORE_ID,
                DiscountTargetType.MENU, DiscountType.AMOUNT, 3000, 42L,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.now());
        return campaign;
    }

    private Campaign menuPercentDiscountCampaign() {
        Campaign campaign = Campaign.create(
                OWNER_ID, STORE_ID,
                DiscountTargetType.MENU, DiscountType.PERCENT, 15, 42L,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                10000L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                LocalDateTime.now());
        return campaign;
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

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
