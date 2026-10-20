package com.launchcatch.campaign.template.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.TemplateSlotValidator;
import com.launchcatch.campaign.template.TemplateStructureValidator;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;

/*
 * BedrockRuntimeClient 는 인터페이스라 래퍼 없이 Mockito로 바로 모킹한다(#182).
 * 검증기(TemplateHtmlSanitizer/TemplateSlotValidator/TemplateStructureValidator)는
 * 의존성 없는 순수 클래스라 실제 구현을 그대로 써서, LLM 응답 고정값이 실제 규칙을
 * 통과하는지까지 같이 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class BedrockTemplateHtmlGeneratorTest {

    private static final String LLM_HTML = """
            <div style="background-color:#FFF5E6;color:#2B2B2B">
              <h1 data-slot="eventName">점심 특선 돈까스 세트</h1>
              <p data-slot="discount">등심 돈까스 세트 15% 할인</p>
              <p data-slot="period">평일 11시~14시</p>
              <img data-slot="image" alt="메뉴 이미지">
            </div>""";

    private static final String PREVIOUS_STORED_HTML = """
            <div style="background-color:#FFF5E6;color:#2B2B2B">
              <span data-slot="adLabel">광고</span>
              <h1 data-slot="eventName">점심 특선 돈까스 세트</h1>
              <p data-slot="discount">등심 돈까스 세트 15% 할인</p>
              <p data-slot="period">평일 11시~14시</p>
              <img data-slot="image" alt="메뉴 이미지">
            </div>""";

    @Mock
    private BedrockRuntimeClient bedrockRuntimeClient;

    private BedrockTemplateHtmlGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new BedrockTemplateHtmlGenerator(
                bedrockRuntimeClient,
                new TemplateHtmlSanitizer(),
                new TemplateSlotValidator(),
                new TemplateStructureValidator());
        ReflectionTestUtils.setField(generator, "modelId", "openai.gpt-oss-120b-1:0");
    }

    private ConverseResponse textResponse(StopReason stopReason, String text) {
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(
                        ContentBlock.fromReasoningContent(ReasoningContentBlock.builder()
                                .reasoningText(ReasoningTextBlock.builder().text("생각 중").build())
                                .build()),
                        ContentBlock.fromText(text))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason(stopReason)
                .build();
    }

    @Test
    void 정상_응답이면_adLabel을_서버가_채워서_돌려준다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, LLM_HTML));

        String result = generator.generate("돈까스 세트 홍보").html();

        assertThat(result).contains("data-slot=\"adLabel\"").contains("광고");
        assertThat(result).contains("data-slot=\"eventName\"");
        verify(bedrockRuntimeClient, times(1)).converse(any(ConverseRequest.class));
    }

    @Test
    void 코드펜스_밖의_설명은_message로_돌려준다() {
        String wrapped = "따뜻한 톤의 카드형으로 만들었어요.\n```html\n" + LLM_HTML + "\n```";
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, wrapped));

        GeneratedTemplate result = generator.generate("돈까스 세트 홍보");

        assertThat(result.message()).isEqualTo("따뜻한 톤의 카드형으로 만들었어요.");
        assertThat(result.html()).contains("data-slot=\"eventName\"");
    }

    @Test
    void message의_꺾쇠와_뒤따르는_두번째_코드펜스는_제거하고_길이를_자른다() {
        String wrapped = "<b>안녕</b>" + "가".repeat(400) + "\n```html\n" + LLM_HTML + "\n```\n<p>x</p>\n```";
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, wrapped));

        String message = generator.generate("돈까스 세트 홍보").message();

        assertThat(message).doesNotContain("<", ">", "```", "x").hasSize(300).startsWith("b안녕/b");
    }

    @Test
    void 코드펜스가_없으면_message는_빈_값이다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, LLM_HTML));

        GeneratedTemplate result = generator.generate("돈까스 세트 홍보");

        assertThat(result.message()).isEmpty();
    }

    @Test
    void 모델이_지시를_어기고_adLabel을_같이_써도_하나만_남는다() {
        String withModelAdLabel = LLM_HTML.replace(
                "<h1 data-slot=\"eventName\">", "<span data-slot=\"adLabel\">광고</span><h1 data-slot=\"eventName\">");
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, withModelAdLabel));

        String result = generator.generate("돈까스 세트 홍보").html();

        assertThat(Jsoup.parseBodyFragment(result).select("[data-slot=adLabel]")).hasSize(1);
    }

    @Test
    void 코드펜스와_설명문이_붙어도_HTML만_뽑아낸다() {
        String wrapped = "네, 요청하신 포스터입니다.\n```html\n" + LLM_HTML + "\n```\n이상입니다.";
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, wrapped));

        String result = generator.generate("돈까스 세트 홍보").html();

        assertThat(result).contains("data-slot=\"eventName\"");
    }

    @Test
    void 설명_문장에_꺾쇠가_섞여도_코드펜스_안의_내용만_뽑아낸다() {
        String wrapped = "10%<20% 할인 비교 참고하세요.\n```html\n" + LLM_HTML + "\n```\n<끝>";
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, wrapped));

        String result = generator.generate("돈까스 세트 홍보").html();

        assertThat(result).contains("data-slot=\"eventName\"");
    }

    @Test
    void 허용_안_된_태그면_1번_재시도하고_고치면_성공한다() {
        String withScript = LLM_HTML.replace("</div>", "<script>alert(1)</script></div>");
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, withScript))
                .thenReturn(textResponse(StopReason.END_TURN, LLM_HTML));

        String result = generator.generate("돈까스 세트 홍보").html();

        assertThat(result).doesNotContain("script");
        verify(bedrockRuntimeClient, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void 허용_안_된_태그가_재시도까지_반복되면_POSTER_011을_던진다() {
        String withScript = LLM_HTML.replace("</div>", "<script>alert(1)</script></div>");
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, withScript));

        assertThatThrownBy(() -> generator.generate("돈까스 세트 홍보"))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.DISALLOWED_ELEMENT);
        verify(bedrockRuntimeClient, times(2)).converse(any(ConverseRequest.class));
    }

    @Test
    void 슬롯이_빠지면_재시도하고_고치면_성공한다() {
        String missingSlot = LLM_HTML.replace("data-slot=\"discount\"", "data-slot=\"x\"");
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, missingSlot))
                .thenReturn(textResponse(StopReason.END_TURN, LLM_HTML));

        String result = generator.generate("돈까스 세트 홍보").html();

        assertThat(result).contains("data-slot=\"discount\"");
    }

    @Test
    void 슬롯_누락이_재시도까지_반복되면_POSTER_001을_던진다() {
        String missingSlot = LLM_HTML.replace("data-slot=\"discount\"", "data-slot=\"x\"");
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, missingSlot));

        assertThatThrownBy(() -> generator.generate("돈까스 세트 홍보"))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.SLOT_CONTRACT_VIOLATION);
    }

    @Test
    void 토큰이_잘리면_재시도하고_짧은_응답이면_성공한다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.MAX_TOKENS, "생각하는 중이었는데 잘림"))
                .thenReturn(textResponse(StopReason.END_TURN, LLM_HTML));

        String result = generator.generate("돈까스 세트 홍보").html();

        assertThat(result).contains("data-slot=\"eventName\"");
    }

    @Test
    void 토큰_잘림이_재시도까지_반복되면_POSTER_010을_던진다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.MAX_TOKENS, "잘림"));

        assertThatThrownBy(() -> generator.generate("돈까스 세트 홍보"))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_TRUNCATED);
    }

    @Test
    void revise는_슬롯_태그가_이전과_다르면_재시도까지_반복돼도_POSTER_002를_던진다() {
        String differentTag = LLM_HTML
                .replace("<h1 data-slot=\"eventName\">", "<p data-slot=\"eventName\">")
                .replace("점심 특선 돈까스 세트</h1>", "점심 특선 돈까스 세트</p>");
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, differentTag));

        assertThatThrownBy(() -> generator.revise(PREVIOUS_STORED_HTML, "색상만 바꿔줘"))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.STRUCTURE_CHANGED);
    }

    @Test
    void revise는_슬롯_태그가_이전과_같으면_성공한다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, LLM_HTML));

        String result = generator.revise(PREVIOUS_STORED_HTML, "색상만 바꿔줘").html();

        assertThat(result).contains("data-slot=\"adLabel\"");
    }

    @Test
    void SDK_호출_타임아웃이면_TemplateGenerationTimeoutException으로_바꾼다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenThrow(ApiCallTimeoutException.create(30_000L));

        assertThatThrownBy(() -> generator.generate("돈까스 세트 홍보"))
                .isInstanceOf(TemplateGenerationTimeoutException.class);
    }

    @Test
    void Throttling이면_재시도_없이_바로_POSTER_012를_던진다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenThrow(ThrottlingException.builder().message("Too many requests").build());

        assertThatThrownBy(() -> generator.generate("돈까스 세트 홍보"))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_THROTTLED);
        verify(bedrockRuntimeClient, times(1)).converse(any(ConverseRequest.class));
    }

    @Test
    void 모델이_아직_준비_안_됐어도_같은_오류로_묶는다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenThrow(ModelNotReadyException.builder().message("Model not ready").build());

        assertThatThrownBy(() -> generator.generate("돈까스 세트 홍보"))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_THROTTLED);
    }

    @Test
    void 가드레일에_막히면_재시도하고_통과하면_성공한다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.GUARDRAIL_INTERVENED, "막힘"))
                .thenReturn(textResponse(StopReason.END_TURN, LLM_HTML));

        String result = generator.generate("돈까스 세트 홍보").html();

        assertThat(result).contains("data-slot=\"eventName\"");
    }

    @Test
    void 콘텐츠_필터가_재시도까지_반복되면_POSTER_013을_던진다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.CONTENT_FILTERED, "막힘"));

        assertThatThrownBy(() -> generator.generate("돈까스 세트 홍보"))
                .isInstanceOf(CampaignException.class)
                .extracting(e -> ((CampaignException) e).getErrorCode())
                .isEqualTo(PosterErrorCode.GENERATION_BLOCKED);
    }

    @Test
    void 시스템_프롬프트의_예시는_정화와_슬롯_검증을_통과하고_A4_비율을_담는다() {
        when(bedrockRuntimeClient.converse(any(ConverseRequest.class)))
                .thenReturn(textResponse(StopReason.END_TURN, LLM_HTML));

        generator.generate("테스트");

        ArgumentCaptor<ConverseRequest> captor = ArgumentCaptor.forClass(ConverseRequest.class);
        verify(bedrockRuntimeClient).converse(captor.capture());
        String systemPrompt = captor.getValue().system().get(0).text();
        String example = systemPrompt.substring(
                        systemPrompt.indexOf("[예시]") + "[예시]".length(), systemPrompt.indexOf("이 예시는"))
                .strip();

        assertThat(systemPrompt).contains("aspect-ratio:210/297");
        assertThat(new TemplateHtmlSanitizer().sanitize(example).removedElements()).isEmpty();
        // 서버가 따로 채우는 adLabel을 붙여야 검증기가 5개 슬롯을 모두 본다
        String withAdLabel = "<span data-slot=\"adLabel\">광고</span>" + example;
        assertThatCode(() -> new TemplateSlotValidator().validate(withAdLabel)).doesNotThrowAnyException();
    }
}
