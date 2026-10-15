package com.launchcatch.campaign.template.client;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import com.launchcatch.campaign.template.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.TemplateSlotValidator;
import com.launchcatch.campaign.template.TemplateStructureValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** ```html ... ``` 또는 ``` ... ``` 코드펜스 안의 내용을 뽑아낸다. */
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:html)?\\s*(.*?)```", Pattern.DOTALL);

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
            너는 점심시간 할인 쿠폰 광고 템플릿의 HTML을 만든다.

            [예시]
            <div style="display:flex;flex-direction:column;width:100%;aspect-ratio:210/297;overflow:hidden;box-sizing:border-box;padding:28px 24px;background:linear-gradient(160deg,#FFF3E0,#FFD9A8);color:#3E2723;font-family:sans-serif;text-align:center;border-radius:24px">
              <h1 data-slot="eventName" style="margin:0 0 16px;font-size:32px;line-height:1.25;font-weight:800;color:#8D2F0B">점심 특선 돈까스 세트</h1>
              <img data-slot="image" alt="메뉴 이미지" style="display:block;flex:1;min-height:0;width:100%;object-fit:cover;border-radius:16px;background-color:#F3D9B8;box-shadow:0 6px 16px rgba(62,39,35,0.25)">
              <p data-slot="discount" style="margin:20px 0 8px;font-size:28px;font-weight:800;color:#D84315">등심 돈까스 세트 15% 할인</p>
              <p data-slot="period" style="margin:0;display:inline-block;padding:6px 16px;font-size:15px;color:#5D4037;background-color:rgba(255,255,255,0.7);border-radius:999px">평일 11시~14시</p>
            </div>
            이 예시는 data-slot 사용법과 완성도의 수준을 보여 주는 참고용이다.
            색상과 배치를 그대로 베끼지 말고 요청에 맞게 새로 정한다.

            [필수 규칙]
            - 다음 4개의 data-slot을 각각 정확히 하나씩 포함한다: eventName, discount, period, image
              (adLabel은 네가 채우지 않는다. 서버가 따로 채운다)
            - eventName, discount, period는 div, span, p, h1, h2, h3, strong, em 중 하나의 태그에 올려라
            - image는 반드시 img 태그에 올려라. src는 쓰지 않는다. alt는 "메뉴 이미지"로 둔다
            - 전체 태그는 div, span, p, h1, h2, h3, strong, em, br, img 만 쓴다.
              a, table, ul, li, button, script, style 같은 태그는 절대 쓰지 않는다
            - 속성은 class, style, data-slot, (img의) alt만 쓴다.
              style 안에 url(), @import, javascript:는 쓰지 않는다
            - 스타일은 전부 style 속성 안에 쓴다. :hover, @media, @keyframes, 웹폰트처럼
              style 태그나 외부 파일이 있어야 하는 것은 쓰지 않는다
            - 루트 div의 가로세로 비율은 A4 세로(가로 210 : 세로 297)로 고정한다.
              루트 style에 aspect-ratio:210/297을 반드시 넣는다. 요청에 다른 비율이 있어도 이 비율은 바꾸지 않는다

            [디자인 지침]
            - 루트는 div 하나로 감싸고 display:flex, flex-direction:column, width:100%, aspect-ratio:210/297,
              overflow:hidden, box-sizing:border-box를 준다. 고정 px 가로 폭과 고정 px 세로 높이는 쓰지 않는다
            - 슬롯과 꾸밈 요소는 세로로 넉넉히 펼쳐 A4 한 장을 채운다. 위쪽에 eventName, 남는 공간은 image,
              아래쪽에 discount와 period를 둔다. 내용이 비율 안에 다 들어가게 글자 크기와 간격을 정한다
            - 여백을 넉넉하게 준다. 루트 padding은 24~32px, 요소 사이 간격은 8~24px로 한다
            - 모서리는 border-radius 12~24px로 둥글리고, 필요하면 box-shadow로 입체감을 준다
            - 배경은 밋밋한 단색만 쓰지 말고 linear-gradient 두 색, 또는 단색에 포인트 띠나 배지(div, span)를 곁들인다
            - 글자 크기로 위계를 만든다. eventName은 28~36px 굵게, discount는 22~30px로 가장 눈에 띄는 강조색,
              period는 14~16px 보조 글자로 한다
            - 글자색과 배경색의 밝기 차이를 충분히 둔다. 밝은 배경에는 어두운 글자, 어두운 배경에는 밝은 글자를 쓴다.
              색은 배경, 글자, 강조색 한 가지를 중심으로 3~4가지 안에서 고른다
            - 글꼴은 font-family에 sans-serif나 serif 기본값만 쓴다
            - image는 width:100%, flex:1, min-height:0, object-fit:cover, border-radius, 배경색(빈 이미지 자리 표시)을 준다.
              고정 px 높이는 쓰지 않는다. 남는 세로 공간을 image가 차지해 A4 비율을 채운다
            - 꾸밈용 div나 span은 몇 개만 쓴다. 슬롯 4개 말고 글자를 더 늘리지 않는다
            - 마크업은 간결하게 쓴다. 전체 40줄 안에서 끝낸다

            [내용]
            - eventName, discount, period는 이 템플릿의 미리보기 예시 문구다.
              요청의 분위기에 맞는 그럴듯한 예시로 채운다(실제 캠페인 값이 아니라 예시임)
            - 한국어로, 짧고 명확하게 쓴다
            - 요청이 정한 분위기, 색, 배치, 형태(카드형, 띠 배너, 큰 숫자 강조 등)가 있으면 위 지침보다 우선한다.
              요청에 없는 부분은 위 지침으로 정한다. 단 A4 비율 규칙은 요청과 상관없이 언제나 지킨다

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
                    "Bedrock Converse 호출이 30초를 넘었다", e);
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
     * 확인한 사실이다(런치캐치_LLM_Bedrock.md). 프롬프트 지시만 믿지 않는다.
     *
     * 코드펜스가 있으면 그 안의 내용만 쓴다. 설명 문장에 꺾쇠(<, >)가 우연히 섞여도
     * 코드펜스 경계가 우선이라 영향이 없다. 코드펜스가 없으면 첫 '<'부터 마지막 '>'까지만
     * 잘라서 쓴다(#182 자체 리뷰로 찾은 보강, 전에는 이 폴백 하나뿐이었다).
     */
    private String extractHtml(String raw) {
        Matcher fenced = CODE_FENCE.matcher(raw);
        if (fenced.find()) {
            return fenced.group(1).trim();
        }
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
     *
     * 넣기 전에 혹시 모델이 지시를 어기고 넣은 adLabel을 먼저 지운다. revise에서는 이전
     * 버전 HTML(서버가 이미 채운 adLabel 포함)을 모델에게 그대로 보여주기 때문에, 모델이
     * 그걸 베껴 같이 돌려줄 수 있다. 안 지우면 adLabel이 2개가 돼 슬롯 검증이
     * SLOT_CONTRACT_VIOLATION으로 잘못 떨어진다(#182 코드 리뷰로 찾음).
     */
    private void insertAdLabel(Document document) {
        document.select("[data-slot=" + AD_LABEL_SLOT + "]").remove();
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
