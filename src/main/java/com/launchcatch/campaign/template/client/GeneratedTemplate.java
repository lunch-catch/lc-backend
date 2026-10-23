package com.launchcatch.campaign.template.client;

import java.util.List;

/*
 * LLM 이 돌려준 결과다. html 은 코드펜스 안의 HTML 이고, message 는 코드펜스 밖의 짧은 설명이다. 코드펜스가
 * 없으면 message 는 빈 문자열이다(null 이 아니다). suggestedPrompts 는 같은 응답에 담긴 후속 제안(퀵 리플라이)이고
 * 없으면 빈 목록이다(#732). message 와 suggestedPrompts 는 검증하지 않은 LLM 문장이다.
 *
 * html 이 null 이면 템플릿을 만들거나 고치라는 요청이 아니라 질문이나 인사에 자연어로만 답한 "대화만 있는 응답"이다
 * (#733). 이때 message 가 답변이고 suggestedPrompts 는 비어 있다.
 */
public record GeneratedTemplate(String html, String message, List<String> suggestedPrompts) {

    public GeneratedTemplate(String html, String message) {
        this(html, message, List.of());
    }

    public static GeneratedTemplate chatOnly(String message) {
        return new GeneratedTemplate(null, message, List.of());
    }

    public boolean chatOnly() {
        return html == null;
    }
}
