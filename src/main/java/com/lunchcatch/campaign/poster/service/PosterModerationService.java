package com.lunchcatch.campaign.poster.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.exception.PosterErrorCode;
import com.lunchcatch.campaign.poster.ForbiddenExpressionDictionary;
import com.lunchcatch.campaign.poster.client.PosterImageInspection;
import com.lunchcatch.campaign.poster.client.PosterImageInspector;
import com.lunchcatch.campaign.poster.dto.PosterModerationOutcome;
import com.lunchcatch.campaign.poster.entity.ModerationFailReason;
import com.lunchcatch.campaign.poster.entity.ModerationResult;
import com.lunchcatch.campaign.poster.entity.Poster;
import com.lunchcatch.campaign.poster.entity.PosterModerationResult;
import com.lunchcatch.campaign.poster.repository.PosterModerationResultRepository;
import com.lunchcatch.campaign.poster.repository.PosterRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/*
 * 포스터 자동 검수다(명세서 57행, #535). 캠페인 활성화(47행)가 부르고, 결과로 캠페인 상태를 바꾸는
 * 일은 부르는 쪽이 한다. 설계와 그 근거는 docs/design/campaign/poster_구조와_동작_설계.md 에 있다.
 *
 * 트랜잭션을 걸지 않는다. 포스터 조회와 결과 저장은 저장소 호출마다 짧은 트랜잭션으로 끝나고,
 * 그 사이의 S3 읽기와 Rekognition 호출은 트랜잭션 밖에서 한다. 외부 호출을 기다리는 동안
 * DB 커넥션을 잡아 두지 않기 위해서다.
 *
 * 걸린 사유는 하나에서 멈추지 않고 모두 모은다. 점주가 한 번 고칠 때 전부 고칠 수 있게 하려는 것이다.
 */
@Service
public class PosterModerationService {

    private static final String IMAGE_SLOT = "image";

    private final PosterRepository posterRepository;
    private final PosterModerationResultRepository posterModerationResultRepository;
    private final ForbiddenExpressionDictionary forbiddenExpressionDictionary;
    private final PosterImageInspector posterImageInspector;
    // 이 저장소에는 ObjectMapper 빈이 없어(PosterUploadService 도 같은 이유로 직접 둔다) 직접 둔다.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final double maxTextRatio;
    private final int minImageWidth;
    private final int minImageHeight;

    public PosterModerationService(
            PosterRepository posterRepository,
            PosterModerationResultRepository posterModerationResultRepository,
            ForbiddenExpressionDictionary forbiddenExpressionDictionary,
            PosterImageInspector posterImageInspector,
            @Value("${campaign.moderation.max-text-ratio}") double maxTextRatio,
            @Value("${campaign.moderation.min-image-width}") int minImageWidth,
            @Value("${campaign.moderation.min-image-height}") int minImageHeight) {
        this.posterRepository = posterRepository;
        this.posterModerationResultRepository = posterModerationResultRepository;
        this.forbiddenExpressionDictionary = forbiddenExpressionDictionary;
        this.posterImageInspector = posterImageInspector;
        this.maxTextRatio = maxTextRatio;
        this.minImageWidth = minImageWidth;
        this.minImageHeight = minImageHeight;
    }

    public PosterModerationOutcome moderate(Long campaignId) {
        Poster poster = posterRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.POSTER_NOT_SAVED));

        Set<ModerationFailReason> reasons = EnumSet.noneOf(ModerationFailReason.class);
        Map<String, String> slotTexts = slotTexts(poster.getSlotValues());
        List<String> posterTexts = new ArrayList<>(slotTexts.values());
        posterTexts.add(poster.getTitle());
        if (forbiddenExpressionDictionary.containsAny(posterTexts)) {
            reasons.add(ModerationFailReason.FORBIDDEN_EXPRESSION);
        }

        PosterImageInspection inspection = posterImageInspector.inspect(poster.getImageObjectKey());
        switch (inspection.status()) {
            case UNREADABLE -> reasons.add(ModerationFailReason.IMAGE_UNREADABLE);
            case TOOL_FAILURE -> reasons.add(ModerationFailReason.MODERATION_TOOL_FAILURE);
            case READ -> {
                if (inspection.width() < minImageWidth || inspection.height() < minImageHeight) {
                    reasons.add(ModerationFailReason.RESOLUTION_TOO_LOW);
                }
                if (inspection.textRatio() > maxTextRatio) {
                    reasons.add(ModerationFailReason.TEXT_RATIO_EXCEEDED);
                }
                if (forbiddenExpressionDictionary.containsAny(inspection.detectedTexts())) {
                    reasons.add(ModerationFailReason.FORBIDDEN_EXPRESSION);
                }
            }
        }

        List<ModerationFailReason> reasonList = List.copyOf(reasons);
        if (reasonList.isEmpty()) {
            posterModerationResultRepository.save(PosterModerationResult.pass(poster.getId()));
            return new PosterModerationOutcome(ModerationResult.PASS, List.of());
        }
        posterModerationResultRepository.save(PosterModerationResult.fail(poster.getId(), reasonList));
        return new PosterModerationOutcome(ModerationResult.FAIL, reasonList);
    }

    /*
     * 이미지 슬롯(URL)을 뺀 텍스트 슬롯의 값만 모은다.
     * HTML 태그와 속성은 보지 않는다.
     */
    private Map<String, String> slotTexts(String slotValuesJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(slotValuesJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("poster slot values를 읽지 못했습니다.", e);
        }
        Map<String, String> texts = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            if (!IMAGE_SLOT.equals(entry.getKey())) {
                texts.put(entry.getKey(), entry.getValue().path("value").asText(""));
            }
        });
        return texts;
    }
}
