package com.launchcatch.campaign.template.client;

/*
 * 관리자의 요청 문장으로 템플릿 HTML 을 만든다.
 * 구현은 응답이 30초를 넘으면 TemplateGenerationTimeoutException 을 던져야 한다.
 */
public interface TemplateHtmlGenerator {

    String generate(String requestPrompt);

    /*
     * 기존 템플릿을 자연어 요청으로 다시 고친다. previousHtml 은 수정 전 최신 버전의 HTML 이다.
     * generate() 와 호출 계약(타임아웃 등)은 같다.
     */
    String revise(String previousHtml, String requestPrompt);
}
