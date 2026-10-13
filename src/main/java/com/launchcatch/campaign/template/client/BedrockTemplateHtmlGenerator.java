package com.launchcatch.campaign.template.client;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import com.launchcatch.campaign.template.service.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.service.TemplateSlotValidator;
import com.launchcatch.campaign.template.service.TemplateStructureValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.InternalServerException;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

/*
 * Bedrock Converse로 LLM이 템플릿 HTML을 직접 쓰게 한다(#182). 슬롯 5개 중 adLabel은
 * 표시광고법상 의무 표기라 LLM에게 맡기지 않고 이 클래스가 강제로 채운다
 * (feed-api-design.md "광고 라벨을 서버가 내려주지 않는다"와 같은 이유로, 여기서는 반대로
 * LLM이 아니라 서버가 그 자리를 직접 채운다).
 *
 * 검증 실패(허용 안 된 태그, 슬롯 누락, 구조 불일치, 토큰 잘림, 가드레일 차단) 다섯 가지
 * 다 같은 대화에 교정 지시를 이어 붙여 1회만 재시도한다. 재시도까지 실패하면 그 시점
 * 오류로 끝낸다.
 */
@Component
@ConditionalOnProperty(name = "bedrock.model-id")
@RequiredArgsConstructor
public class BedrockTemplateHtmlGenerator implements TemplateHtmlGenerator {

    private static final int MAX_OUTPUT_TOKENS = 4000;
    private static final int MAX_RETRIES = 1;
    private static final String AD_LABEL_SLOT = "adLabel";
    private static final String AD_LABEL_TEXT = "광고";

    private static final String TRUNCATED_CORRECTION =
            "방금 답변이 끝맺지 못하고 중간에 잘렸다. 생각 과정과 설명 없이, 더 짧고 간결하게 HTML만 바로 써라.";

    private static final String BLOCKED_CORRECTION =
            "방금 답변이 가드레일에 막혀 생성되지 않았다. 민감하거나 부적절할 수 있는 표현 없이, "
                    + "평범한 광고 문구로 다시 써라.";

    private static final String SLOT_CORRECTION =
            "방금 만든 HTML에 eventName, discount, period, image 슬롯 중 하나가 없거나 2개 이상 있었다. "
                    + "4개의 data-slot을 각각 정확히 하나씩 포함해서 다시 만들어라.";

    private static final String STRUCTURE_CORRECTION =
            "방금 만든 HTML에서 슬롯 태그가 이전 버전과 달라졌다. "
                    + "이전 버전의 슬롯 태그를 그대로 유지해서 다시 만들어라.";

    private static final String SYSTEM_PROMPT = """
            너는 점심시간 할인 쿠폰 광고 포스터의 HTML을 만든다.

            [예시]
            <div style="background-color:#FFF5E6;color:#2B2B2B">
              <h1 data-slot="eventName">점심 특선 돈까스 세트</h1>
              <p data-slot="discount">등심 돈까스 세트 15% 할인</p>
              <p data-slot="period">평일 11시~14시</p>
              <img data-slot="image" alt="메뉴 이미지">
            </div>
            이 예시처럼 data-slot 속성을 정확히 써라.

            [필수 규칙]
            - 다음 4개의 data-slot을 각각 정확히 하나씩 포함한다: eventName, discount, period, image
              (adLabel은 네가 채우지 않는다. 서버가 따로 채운다)
            - eventName, discount, period는 div, span, p, h1, h2, h3, strong, em 중 하나의 태그에 올려라
            - image는 반드시 img 태그에 올려라. src는 쓰지 않는다. alt는 "메뉴 이미지"로 둔다
            - 전체 태그는 div, span, p, h1, h2, h3, strong, em, br, img 만 쓴다.
              a, table, ul, li, button, script, style 같은 태그는 절대 쓰지 않는다
            - 속성은 class, style, data-slot, (img의) alt만 쓴다.
              style 안에 url(), @import, javascript:는 쓰지 않는다

            [내용]
            - eventName, discount, period는 이 템플릿의 미리보기 예시 문구다.
              요청의 분위기에 맞는 그럴듯한 예시로 채운다(실제 캠페인 값이 아니라 예시임)
            - 한국어로, 짧고 명확하게 쓴다
            - 레이아웃, 색상, 배치는 요청의 분위기에 맞게 네가 자유롭게 정한다

            [출력 형식]
            - HTML 코드만 답한다. 설명 문장이나 코드펜스(```)를 앞뒤에 절대 붙이지 않는다""";

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final TemplateHtmlSanitizer templateHtmlSanitizer;
    private final TemplateSlotValidator templateSlotValidator;
    private final TemplateStructureValidator templateStructureValidator;

    @Value("${bedrock.model-id}")
    private String modelId;

    @Override
    public String generate(String requestPrompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(userMessage(requestPrompt));
        return runConversation(SYSTEM_PROMPT, messages, null);
    }

    @Override
    public String revise(String previousHtml, String requestPrompt) {
        Document previousDocument = Jsoup.parseBodyFragment(previousHtml);
        Map<String, String> previousTags = templateStructureValidator.slotTags(previousDocument);
        String systemPrompt = SYSTEM_PROMPT + reviseAddendum(previousTags);
        List<Message> messages = new ArrayList<>();
        messages.add(userMessage("기존 템플릿:\n" + previousHtml + "\n\n수정 요청: " + requestPrompt));
        return runConversation(systemPrompt, messages, previousDocument);
    }

    private String reviseAddendum(Map<String, String> previousTags) {
        String tagList = previousTags.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(AD_LABEL_SLOT))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        return """


                이번은 기존 템플릿 수정이다. 위 기존 템플릿을 참고해서 요청대로 바꾼다.
                이전 버전의 슬롯 태그: %s. 이 태그 종류를 그대로 유지한다.
                그 외 레이아웃, 색상, 꾸밈, 문구는 자유롭게 바꿔도 된다.""".formatted(tagList);
    }

    /*
     * 재시도까지 포함해 최대 2번 부른다. 매번 Bedrock에 보내는 요청과 검증 순서가 같아서
     * 한 메서드에 모았다. previousDocument가 null이면 생성(구조 비교 생략), 아니면 수정이다.
     */
    private String runConversation(String systemPrompt, List<Message> messages, Document previousDocument) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            boolean lastAttempt = attempt == MAX_RETRIES;
            ConverseResponse response = converse(systemPrompt, messages);
            String rawText = textOf(response);
            messages.add(assistantMessage(rawText));

            if (response.stopReason() == StopReason.MAX_TOKENS) {
                if (lastAttempt) {
                    throw new CampaignException(PosterErrorCode.GENERATION_TRUNCATED);
                }
                messages.add(userMessage(TRUNCATED_CORRECTION));
                continue;
            }

            if (response.stopReason() == StopReason.CONTENT_FILTERED
                    || response.stopReason() == StopReason.GUARDRAIL_INTERVENED) {
                if (lastAttempt) {
                    throw new CampaignException(PosterErrorCode.GENERATION_BLOCKED);
                }
                messages.add(userMessage(BLOCKED_CORRECTION));
                continue;
            }

            Document document = Jsoup.parseBodyFragment(extractHtml(rawText));
            TemplateSanitizeResult sanitized = templateHtmlSanitizer.sanitize(document);
            if (!sanitized.removedElements().isEmpty()) {
                if (lastAttempt) {
                    throw new CampaignException(PosterErrorCode.DISALLOWED_ELEMENT);
                }
                messages.add(userMessage(disallowedElementCorrection(sanitized.removedElements())));
                continue;
            }

            insertAdLabel(document);

            try {
                templateSlotValidator.validate(document);
                if (previousDocument != null) {
                    templateStructureValidator.validate(document, previousDocument);
                }
            } catch (CampaignException e) {
                if (lastAttempt) {
                    throw e;
                }
                boolean structureIssue = e.getErrorCode() == PosterErrorCode.STRUCTURE_CHANGED;
                messages.add(userMessage(structureIssue ? STRUCTURE_CORRECTION : SLOT_CORRECTION));
                continue;
            }

            return document.body().html();
        }
        throw new IllegalStateException("unreachable: retry loop must return or throw");
    }

    private ConverseResponse converse(String systemPrompt, List<Message> messages) {
        ConverseRequest request = ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.fromText(systemPrompt))
                .messages(messages)
                .inferenceConfig(InferenceConfiguration.builder().maxTokens(MAX_OUTPUT_TOKENS).build())
                .build();
        try {
            return bedrockRuntimeClient.converse(request);
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
            throw new TemplateGenerationTimeoutException(
                    "Bedrock Converse 호출이 제한 시간(시도당 30초, 전체 45초)을 넘었다", e);
        } catch (ThrottlingException | ServiceUnavailableException | ModelTimeoutException
                | InternalServerException | ModelNotReadyException e) {
            /*
             * 전부 일시적이라 잠시 후 재시도하면 될 수 있는 실패다. 재시도(교정 지시를 덧붙여
             * 다시 부르는 것)는 "내용을 고치면 해결되는 문제"에만 의미가 있어, 여기서는 1회
             * 재시도 없이 바로 끝낸다. AccessDeniedException, ValidationException 같은 설정
             * 실수나 코드 버그 쪽은 여기서 잡지 않고 GlobalExceptionHandler의 예상 못한 실패
             * 경로로 넘겨 스택을 남긴다.
             */
            throw new CampaignException(PosterErrorCode.GENERATION_THROTTLED, e);
        }
    }

    /** reasoningContent 블록은 text()가 null이라 자연스럽게 걸러진다. */
    private String textOf(ConverseResponse response) {
        return response.output().message().content().stream()
                .map(ContentBlock::text)
                .filter(text -> text != null)
                .collect(Collectors.joining());
    }

    /*
     * 모델이 설명이나 코드펜스(```html)를 앞뒤에 붙일 수 있다는 건 인프라가 실측으로
     * 확인한 사실이다(런치캐치_LLM_Bedrock.md). 프롬프트 지시만 믿지 않고 첫 '<'부터
     * 마지막 '>'까지만 잘라서 쓴다.
     */
    private String extractHtml(String raw) {
        int start = raw.indexOf('<');
        int end = raw.lastIndexOf('>');
        if (start < 0 || end < 0 || end < start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }

    /*
     * body 바로 아래 첫 자식으로 둔다. LLM이 만든 루트 래퍼 안에 넣으면 배경색을 그대로
     * 물려받지만, 루트가 항상 div 하나라고 가정할 수 없어 더 단순하고 항상 유효한
     * 위치(body 바로 아래)를 쓴다. 시각적으로는 꾸밈이 덜할 수 있어도 구조는 항상 맞다.
     */
    private void insertAdLabel(Document document) {
        Element adLabel = document.createElement("span").attr("data-slot", AD_LABEL_SLOT).text(AD_LABEL_TEXT);
        document.body().prependChild(adLabel);
    }

    private String disallowedElementCorrection(List<String> removedElements) {
        return "방금 만든 HTML에 허용되지 않은 태그나 속성(" + String.join(", ", removedElements) + ")이 있었다. "
                + "div, span, p, h1, h2, h3, strong, em, br, img 태그와 class, style, data-slot, "
                + "(img의) alt 속성만 써서 다시 만들어라.";
    }

    private Message userMessage(String text) {
        return Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText(text)).build();
    }

    private Message assistantMessage(String text) {
        return Message.builder().role(ConversationRole.ASSISTANT).content(ContentBlock.fromText(text)).build();
    }
}
