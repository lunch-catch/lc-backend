package com.launchcatch.campaign.template.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/*
 * bedrock.model-id가 없을 때(로컬 등) 쓰는 가짜 구현이다.
 * 요청 문장과 상관없이 검증을 모두 통과하는 고정 HTML 을 돌려준다.
 * BedrockTemplateHtmlGenerator와 같은 타입(TemplateHtmlGenerator)을 두고 쓰기 때문에,
 * 어느 빈이 먼저 평가되는지에 기대지 않도록 둘 다 같은 설정값만 보는
 * @ConditionalOnProperty로 서로 반대 조건을 건다(#182).
 *
 * havingValue를 빈 문자열로 두면 스프링이 "값을 안 준 것"과 구분하지 못해, 모델 ID가 있어도
 * "false"만 아니면 통과하는 기본 동작으로 돌아간다(실제 모델 ID로 떠 있어도 이 빈이 같이
 * 뜨는 사고로 이어진다, TemplateHtmlGeneratorWiringTest로 확인). 실제 모델 ID와 절대 같을 수
 * 없는 문자열을 havingValue로 둬서, 값이 있으면 반드시 거부되게 한다.
 */
@Component
@ConditionalOnProperty(name = "bedrock.model-id", havingValue = "__absent__", matchIfMissing = true)
public class FakeTemplateHtmlGenerator implements TemplateHtmlGenerator {

    private static final String HTML = """
            <div class="poster" style="background-color:#FFFFFF;color:#000000">
              <span data-slot="adLabel">광고</span>
              <h1 data-slot="eventName">이벤트명</h1>
              <p data-slot="discount">할인 내용</p>
              <p data-slot="period">기간</p>
              <img data-slot="image" alt="메뉴 이미지">
            </div>
            """;

    @Override
    public String generate(String requestPrompt) {
        return HTML;
    }

    @Override
    public String revise(String previousHtml, String requestPrompt) {
        return HTML;
    }
}
