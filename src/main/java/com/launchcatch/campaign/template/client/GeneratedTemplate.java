package com.launchcatch.campaign.template.client;

import java.util.List;

/*
 * LLM 이 돌려준 결과다. html 은 코드펜스 안의 HTML 이고, message 는 코드펜스 밖의 짧은 설명이다. 코드펜스가
 * 없으면 message 는 빈 문자열이다(null 이 아니다). suggestedPrompts 는 같은 응답에 담긴 후속 제안(퀵 리플라이)이고
 * 없으면 빈 목록이다(#732). message 와 suggestedPrompts 는 검증하지 않은 LLM 문장이다.
 */
public record GeneratedTemplate(String html, String message, List<String> suggestedPrompts) {

    public GeneratedTemplate(String html, String message) {
        this(html, message, List.of());
    }
}
