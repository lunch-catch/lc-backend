package com.launchcatch.campaign.template.client;

/*
 * 관리자의 요청 문장으로 템플릿 HTML 을 만든다.
 * 구현은 응답이 30초를 넘으면 TemplateGenerationTimeoutException 을 던져야 한다.
 */
public interface TemplateHtmlGenerator {

    String generate(String requestPrompt);
}
