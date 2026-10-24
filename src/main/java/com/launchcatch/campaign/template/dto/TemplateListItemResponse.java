package com.launchcatch.campaign.template.dto;

import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Map;

public record TemplateListItemResponse(
        @Schema(description = "템플릿 ID입니다.", example = "1")
        Long templateId,

        @Schema(description = "템플릿 이름입니다.", example = "가을 신메뉴")
        String name,

        @Schema(description = "템플릿 상태입니다.", example = "DRAFT")
        TemplateStatus status,

        @Schema(description = "활성화 여부입니다.", example = "false")
        boolean active,

        @Schema(description = "템플릿 생성 시각입니다.")
        LocalDateTime createdAt,

        @Schema(description = "활성 여부를 마지막으로 바꾼 관리자 ID입니다. 바꾼 적이 없으면 null입니다.", example = "1")
        Long activatedBy,

        @Schema(description = "활성 여부를 마지막으로 바꾼 관리자 이름입니다. 바꾼 적이 없거나 관리자를 찾을 수 없으면 null입니다.", example = "홍길동")
        String activatedByName,

        @Schema(description = "활성 여부를 마지막으로 바꾼 시각입니다. 바꾼 적이 없으면 null입니다.")
        LocalDateTime activatedAt,

        @Schema(description = "마지막으로 임시저장한 관리자 ID입니다.", example = "1")
        Long lastModifiedBy,

        @Schema(description = "마지막으로 임시저장한 관리자 이름입니다. 관리자를 찾을 수 없으면 null입니다.", example = "홍길동")
        String lastModifiedByName,

        @Schema(description = "마지막으로 임시저장한 시각입니다.")
        LocalDateTime lastModifiedAt,

        @Schema(description = "살아 있는 임시저장 버전이 있는지 여부입니다. 게시된 템플릿을 수정하는 중이면 true입니다.", example = "false")
        boolean hasDraft,

        @Schema(description = "게시된 템플릿은 게시된 HTML, DRAFT 템플릿은 최신 버전의 HTML입니다. 목록에서 미리보기로 쓴다.")
        String html
) {

    /*
     * html 은 상태에 따라 가져오는 곳이 다르므로(게시된 템플릿은 Template.htmlContent,
     * DRAFT 는 최신 버전) 호출하는 쪽에서 미리 가려 넘긴다.
     */
    public static TemplateListItemResponse of(Template template, String html, boolean hasDraft, Map<Long, String> adminNames) {
        return new TemplateListItemResponse(
                template.getId(),
                template.getName(),
                template.getStatus(),
                template.isActive(),
                template.getCreatedAt(),
                template.getActivatedBy(),
                nameOf(adminNames, template.getActivatedBy()),
                template.getActivatedAt(),
                template.getLastModifiedBy(),
                nameOf(adminNames, template.getLastModifiedBy()),
                template.getLastModifiedAt(),
                hasDraft,
                html
        );
    }

    private static String nameOf(Map<Long, String> adminNames, Long adminId) {
        return adminId == null ? null : adminNames.get(adminId);
    }
}
