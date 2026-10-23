package com.launchcatch.campaign.template.client;

import java.util.List;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/*
 * bedrock.model-id가 없을 때(로컬 등) 쓰는 가짜 구현이다.
 * 요청 문장과 상관없이 검증을 모두 통과하는 고정 HTML 을 돌려준다.
 * BedrockTemplateHtmlGenerator와 같은 타입(TemplateHtmlGenerator)을 두고 쓰기 때문에,
 * 어느 빈이 먼저 평가되는지에 기대지 않도록 둘 다 같은 설정값만 보는 조건으로 서로 반대를
 * 건다(#182). BedrockModelIdAbsentCondition 주석에 이 조건을 고른 이유가 있다.
 */
@Component
@Conditional(BedrockModelIdAbsentCondition.class)
public class FakeTemplateHtmlGenerator implements TemplateHtmlGenerator {

    private static final String HTML = """
            <div class="poster" style="background-color:#FFFFFF;color:#000000">
              <h1 data-slot="eventName">이벤트명</h1>
              <p data-slot="discount">할인 내용</p>
              <p data-slot="period">기간</p>
              <img data-slot="image" alt="메뉴 이미지">
            </div>
            """;

    private static final String MESSAGE = "요청하신 분위기로 템플릿을 만들었어요.";
    private static final List<String> SUGGESTED_PROMPTS =
            List.of("행사명 글자를 조금 더 크게 해줘", "배경을 조금 더 밝게 해줘");

    @Override
    public GeneratedTemplate generate(String requestPrompt) {
        return new GeneratedTemplate(HTML, MESSAGE, SUGGESTED_PROMPTS);
    }

    @Override
    public GeneratedTemplate revise(String previousHtml, String requestPrompt) {
        return new GeneratedTemplate(HTML, MESSAGE, SUGGESTED_PROMPTS);
    }
}
