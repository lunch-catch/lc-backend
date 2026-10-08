package com.launchcatch.campaign.template.client;

import org.springframework.stereotype.Component;

/*
 * 실제 LLM 연동 전까지 쓰는 가짜 구현이다.
 * 요청 문장과 상관없이 검증을 모두 통과하는 고정 HTML 을 돌려준다.
 * [Feat] LLM 호출 Bedrock 교체 이슈에서 이 클래스를 실제 구현으로 바꾼다.
 */
@Component
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
}
