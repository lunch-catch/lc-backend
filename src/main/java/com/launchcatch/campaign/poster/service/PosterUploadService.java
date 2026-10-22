package com.launchcatch.campaign.poster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.poster.PosterImageUrlResolver;
import com.launchcatch.campaign.poster.dto.PosterCreateResponse;
import com.launchcatch.campaign.poster.dto.PosterImagePrepareUploadResponse;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.owner.contract.OwnerAccountStatus;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.contract.StoreQueryService;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 점주의 포스터 이미지 업로드와 포스터 생성(#376). store의 가게 이미지 업로드(#375, #389)와
 * 같은 presigned PUT 패턴을 쓰지만, 업로드 확인을 위한 별도 confirm 엔드포인트를 두지 않는다.
 * 이슈가 "업로드 presign API"와 "templateId+objectKey로 생성하는 API" 둘만 요구해서,
 * headObject 확인을 생성 시점에 한 번만 한다. 그래서 PENDING 상태를 들고 있는 엔티티도 없다.
 */
@Service
@Slf4j
public class PosterUploadService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final int TITLE_MAX_LENGTH = 100;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final Duration PRESIGN_DURATION = Duration.ofMinutes(10);
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("MM.dd");

    private final CampaignRepository campaignRepository;
    private final TemplateRepository templateRepository;
    private final PosterRepository posterRepository;
    private final StoreQueryService storeQueryService;
    private final OwnerStatusQuery ownerStatusQuery;
    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    // 이 저장소에는 ObjectMapper 빈이 없어(다른 서비스도 쓰지 않음) 직접 둔다.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TransactionTemplate transactionTemplate;
    private final PosterImageUrlResolver imageUrlResolver;

    @Value("${s3.media-bucket}")
    private String mediaBucket;

    public PosterUploadService(
            CampaignRepository campaignRepository, TemplateRepository templateRepository,
            PosterRepository posterRepository, StoreQueryService storeQueryService,
            OwnerStatusQuery ownerStatusQuery, S3Presigner s3Presigner, S3Client s3Client,
            TransactionTemplate transactionTemplate,
            @Value("${cdn.base-url:}") String cdnBaseUrl) {
        this.campaignRepository = campaignRepository;
        this.templateRepository = templateRepository;
        this.posterRepository = posterRepository;
        this.storeQueryService = storeQueryService;
        this.ownerStatusQuery = ownerStatusQuery;
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.transactionTemplate = transactionTemplate;
        this.imageUrlResolver = new PosterImageUrlResolver(cdnBaseUrl);
    }

    public PosterImagePrepareUploadResponse prepareImageUpload(
            Long ownerId, Long campaignId, String contentType, Long fileSize) {
        validateActiveOwner(ownerId);
        validateContentType(contentType);
        validateFileSize(fileSize);

        Campaign campaign = findDraftCampaign(ownerId, campaignId);
        requirePeriod(campaign);

        String objectKey = buildObjectKey(campaignId, contentType);
        String uploadUrl = presign(objectKey, contentType, fileSize);
        return new PosterImagePrepareUploadResponse(objectKey, uploadUrl);
    }

    public PosterCreateResponse createOrOverwritePoster(Long ownerId, Long campaignId, Long templateId, String objectKey) {
        validateActiveOwner(ownerId);
        validateObjectKey(campaignId, objectKey);

        Campaign campaign = findDraftCampaign(ownerId, campaignId);
        requirePeriod(campaign);
        Template template = findUsableTemplate(templateId);

        // 캠페인 잠금과 상관없는 다른 도메인 조회라 잠금을 잡기 전에 끝낸다(셀프 리뷰로 찾음).
        String eventName = findStore(campaign.getId(), campaign.getStoreId()).storeName();
        String discount = formatDiscount(campaign);
        String period = formatPeriod(campaign);

        HeadObjectResponse headObject;
        try {
            // headObject는 응답이 지연될 수 있어 DB 트랜잭션 밖에서 부른다(#389와 같은 근거).
            headObject = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(mediaBucket)
                    .key(objectKey)
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            throw new CampaignException(PosterErrorCode.POSTER_IMAGE_NOT_UPLOADED);
        }
        // 접두어 검사로 이 캠페인 몫의 키임은 보장되지만, 실제로 올라간 파일 종류는 다시 확인한다(셀프 리뷰로 찾음).
        if (!ALLOWED_CONTENT_TYPES.contains(headObject.contentType())) {
            throw new CampaignException(PosterErrorCode.POSTER_IMAGE_CONTENT_TYPE_NOT_ALLOWED);
        }
        if (headObject.contentLength() == null || headObject.contentLength() > MAX_FILE_SIZE) {
            throw new CampaignException(PosterErrorCode.POSTER_IMAGE_FILE_TOO_LARGE);
        }

        return transactionTemplate.execute(
                status -> save(ownerId, campaignId, templateId, template, eventName, discount, period, objectKey));
    }

    /*
     * 외부 호출(headObject) 뒤에 다시 잠금을 잡고 캠페인 상태를 재확인한다. 그 사이 다른
     * 요청이 캠페인 상태를 바꿨을 수 있어서다(#389 confirmMenuImageUpload와 같은 근거).
     * eventName/discount/period는 잠금을 잡기 전에 구한 값을 그대로 쓴다. 재확인 대상은
     * 캠페인이 여전히 이 점주의 DRAFT인지뿐이다.
     */
    private PosterCreateResponse save(Long ownerId, Long campaignId, Long templateId, Template template,
            String eventName, String discount, String period, String objectKey) {
        findDraftCampaignForUpdate(ownerId, campaignId);

        String imageUrl = imageUrlResolver.resolve(objectKey);
        String title = truncateTitle(eventName + " - " + template.getName());

        RenderedPoster rendered = render(template.getHtmlContent(), eventName, discount, period, imageUrl);

        Poster poster = posterRepository.findByCampaignId(campaignId)
                .map(existing -> {
                    existing.overwrite(templateId, title, rendered.slotValuesJson(), rendered.html(), objectKey);
                    return existing;
                })
                .orElseGet(() -> posterRepository.save(
                        Poster.create(campaignId, templateId, title, rendered.slotValuesJson(),
                                rendered.html(), objectKey)));

        return new PosterCreateResponse(poster.getId(), campaignId, templateId, title, rendered.html());
    }

    private Campaign findDraftCampaign(Long ownerId, Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        requireDraft(campaign);
        return campaign;
    }

    private Campaign findDraftCampaignForUpdate(Long ownerId, Long campaignId) {
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        requireDraft(campaign);
        return campaign;
    }

    private void requireDraft(Campaign campaign) {
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new CampaignException(CampaignErrorCode.CAMPAIGN_STATE_NOT_ALLOWED);
        }
    }

    // 캠페인 등록 1단계만 끝낸 DRAFT는 집행 기간이 비어 있을 수 있다(V3__campaign_draft_nullable.sql).
    // discount/period 문구를 만들기 전에 막아야 널 포인터가 아니라 업무 오류로 알려준다(코드래빗 리뷰로 찾음).
    private void requirePeriod(Campaign campaign) {
        if (campaign.getStartDate() == null || campaign.getEndDate() == null) {
            throw new CampaignException(PosterErrorCode.CAMPAIGN_PERIOD_NOT_SET);
        }
    }

    private Template findUsableTemplate(Long templateId) {
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() != TemplateStatus.PUBLISHED || !template.isActive()) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_USABLE_FOR_POSTER);
        }
        return template;
    }

    private StoreInfo findStore(Long campaignId, Long storeId) {
        return storeQueryService.findAllById(List.of(storeId)).stream()
                .findFirst()
                .orElseThrow(() -> {
                    log.error("event=POSTER_STORE_NOT_FOUND campaignId={} storeId={}", campaignId, storeId);
                    return new CampaignException(CommonErrorCode.INTERNAL_ERROR);
                });
    }

    private String formatDiscount(Campaign campaign) {
        String valueText = campaign.getDiscountType() == DiscountType.PERCENT
                ? campaign.getDiscountValue() + "%"
                : campaign.getDiscountValue() + "원";
        String targetText = campaign.getDiscountTargetType() == DiscountTargetType.ALL
                ? "전체 메뉴"
                : findMenuName(campaign.getId(), campaign.getTargetMenuId());
        return targetText + " " + valueText + " 할인";
    }

    private String findMenuName(Long campaignId, Long menuId) {
        return storeQueryService.findMenusByIdIn(List.of(menuId)).stream()
                .findFirst()
                .map(StoreMenuInfo::menuName)
                .orElseThrow(() -> {
                    log.error("event=POSTER_MENU_NOT_FOUND campaignId={} menuId={}", campaignId, menuId);
                    return new CampaignException(CommonErrorCode.INTERNAL_ERROR);
                });
    }

    private String formatPeriod(Campaign campaign) {
        return campaign.getStartDate().format(PERIOD_FORMAT) + "~" + campaign.getEndDate().format(PERIOD_FORMAT);
    }

    private String truncateTitle(String title) {
        return title.length() > TITLE_MAX_LENGTH ? title.substring(0, TITLE_MAX_LENGTH) : title;
    }

    // 이미지 칸 크기 보정 속성이다(#731). withImageFit 주석 참고.
    private static final Map<String, String> IMAGE_FIT_STYLE = new LinkedHashMap<>();

    static {
        IMAGE_FIT_STYLE.put("width", "100%");
        IMAGE_FIT_STYLE.put("height", "100%");
        IMAGE_FIT_STYLE.put("min-height", "0");
        IMAGE_FIT_STYLE.put("object-fit", "cover");
    }

    private RenderedPoster render(String templateHtml, String eventName, String discount, String period,
            String imageUrl) {
        Document document = Jsoup.parseBodyFragment(templateHtml);
        // 광고 표시를 넣지 않기로 했다(#628). 광고 표시가 들어 있는 옛 템플릿으로 만든 포스터에서도 뺀다.
        document.select("[data-slot=adLabel]").remove();
        Map<String, PosterSlotValue> slotValues = new LinkedHashMap<>();
        slotValues.put("eventName", fillText(document, "eventName", eventName));
        slotValues.put("discount", fillText(document, "discount", discount));
        slotValues.put("period", fillText(document, "period", period));
        slotValues.put("image", fillImage(document, imageUrl));
        return new RenderedPoster(document.body().html(), writeSlotValues(slotValues));
    }

    private PosterSlotValue fillText(Document document, String slot, String value) {
        requireSlot(document, slot).text(value);
        return new PosterSlotValue("text", value);
    }

    private PosterSlotValue fillImage(Document document, String imageUrl) {
        Element image = requireSlot(document, "image");
        image.attr("src", imageUrl);
        image.attr("style", withImageFit(image.attr("style")));
        return new PosterSlotValue("image", imageUrl);
    }

    /*
     * 큰 이미지가 이미지 칸을 밀어내 포스터가 깨지지 않게 크기 스타일을 서버가 보정한다(#731). 칸의 크기는
     * 템플릿(LLM 이 만든 CSS)이 정하고 서버는 업로드 파일의 가로세로 크기를 제한하지 않으므로, 템플릿이
     * 이 속성을 빠뜨려도 안전하도록 항상 덧씌운다. cover 는 비율을 유지한 채 칸을 채우고 넘치는 부분을
     * 자른다(왜곡 없음).
     *
     * 템플릿이 이미 가진 이미지 스타일(border-radius, box-shadow 등)은 그대로 두고, 보정 속성이 이미
     * 있으면 서버 값으로 덮어쓴다. 같은 속성이 두 번 들어가지 않는다. 이 메서드는 템플릿 HTML 에서 매번
     * 새로 렌더링하므로 덮어쓰는 포스터에서도 중복되지 않는다.
     */
    private String withImageFit(String existingStyle) {
        Map<String, String> declarations = new LinkedHashMap<>();
        for (String declaration : existingStyle.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            declarations.put(declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT), declaration.trim());
        }
        IMAGE_FIT_STYLE.forEach((property, value) -> declarations.put(property, property + ":" + value));
        return String.join(";", declarations.values());
    }

    // 템플릿은 게시 전에 이미 슬롯 검증을 거치므로, 여기서 못 찾으면 그 사이 데이터가 깨진 것이다.
    private Element requireSlot(Document document, String slot) {
        Elements found = document.select("[data-slot=" + slot + "]");
        if (found.isEmpty()) {
            throw new CampaignException(PosterErrorCode.SLOT_CONTRACT_VIOLATION);
        }
        return found.first();
    }

    private String writeSlotValues(Map<String, PosterSlotValue> slotValues) {
        try {
            return objectMapper.writeValueAsString(slotValues);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("poster slot values를 JSON으로 만들지 못했습니다.", e);
        }
    }

    private String buildObjectKey(Long campaignId, String contentType) {
        String extension = contentType.equals("image/png") ? "png" : "jpg";
        return objectKeyPrefix(campaignId) + UUID.randomUUID() + "." + extension;
    }

    // UUID 부분은 추측할 수 없어, 이 접두어 검사만으로 이 캠페인의 prepareUpload가 실제로
    // 발급한 키인지가 보장된다(셀프 리뷰로 찾음 — 다른 캠페인·다른 객체의 키를 그대로 가져다
    // 쓸 수 있는 문제였다).
    private void validateObjectKey(Long campaignId, String objectKey) {
        if (!objectKey.startsWith(objectKeyPrefix(campaignId))) {
            throw new CampaignException(PosterErrorCode.POSTER_IMAGE_OBJECT_KEY_INVALID);
        }
    }

    private String objectKeyPrefix(Long campaignId) {
        return "campaigns/" + campaignId + "/poster/";
    }

    private String presign(String objectKey, String contentType, Long fileSize) {
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(mediaBucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(fileSize)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(PRESIGN_DURATION)
                .putObjectRequest(objectRequest)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    private void validateContentType(String contentType) {
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new CampaignException(PosterErrorCode.POSTER_IMAGE_CONTENT_TYPE_NOT_ALLOWED);
        }
    }

    private void validateFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw new CampaignException(PosterErrorCode.POSTER_IMAGE_FILE_TOO_LARGE);
        }
    }

    private void validateActiveOwner(Long ownerId) {
        if (ownerStatusQuery.findCurrentStatus(ownerId).orElse(null) != OwnerAccountStatus.ACTIVE) {
            throw new CampaignException(CampaignErrorCode.OWNER_NOT_ACTIVE);
        }
    }

    private record PosterSlotValue(String type, String value) {
    }

    private record RenderedPoster(String html, String slotValuesJson) {
    }
}
