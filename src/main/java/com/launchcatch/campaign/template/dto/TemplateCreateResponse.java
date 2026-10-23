package com.launchcatch.campaign.template.dto;

import com.launchcatch.campaign.template.entity.TemplateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record TemplateCreateResponse(
        @Schema(description = "템플릿 ID입니다. 템플릿을 만들거나 고치라는 요청이 아니어서 자연어로만 응답한 경우 null입니다.", example = "1")
        Long templateId,

        @Schema(description = "템플릿 이름입니다. 자연어로만 응답한 경우 null입니다.", example = "가을 신메뉴")
        String name,

        @Schema(description = "저장된 임시저장 버전 번호입니다. 자연어로만 응답한 경우 새 버전을 만들지 않아 null입니다.", example = "1")
        Integer versionNumber,

        @Schema(description = "템플릿 상태입니다. 생성 직후는 항상 DRAFT입니다. 자연어로만 응답한 경우 null입니다.", example = "DRAFT")
        TemplateStatus status,

        @Schema(description = "검증을 거쳐 저장된 HTML입니다. 자연어로만 응답한 경우 null입니다.")
        String html,

        @Schema(description = "LLM이 HTML과 함께 보낸 설명입니다. 검증하지 않은 문장이라 텍스트로만 보여줘야 합니다. 설명이 없으면 빈 문자열입니다.",
                example = "따뜻한 주황 톤의 카드형 템플릿으로 만들었어요.")
        String message,

        @Schema(description = "정화 과정에서 제거된 태그와 속성입니다. 없으면 빈 배열입니다.", example = "[\"script\", \"div[onclick]\"]")
        List<String> removedElements,

        @Schema(description = "퀵 리플라이 버튼으로 보여줄 수정 요청 문장입니다. 모델이 이번 요청과 결과에 맞춰 만든 제안이며 2~3개입니다. 더 조정할 것이 없거나 같은 requestId 의 재요청이면 빈 배열입니다.",
                example = "[\"배경을 더 밝게 해줘\", \"행사명 글자를 더 크게 해줘\"]")
        List<String> suggestedPrompts
) {

    /*
     * 템플릿을 만들거나 고치라는 요청이 아니어서 자연어로만 응답한 경우다(#733). 템플릿과 버전을 만들지 않으므로
     * message 외의 값은 비어 있다.
     */
    public static TemplateCreateResponse chatOnly(String message) {
        return new TemplateCreateResponse(null, null, null, null, null, message, List.of(), List.of());
    }
}
