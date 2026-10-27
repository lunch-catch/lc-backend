package com.lunchcatch.campaign.template.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "template_version")
@AttributeOverride(name = "id", column = @Column(name = "template_version_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TemplateVersion extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private Template template;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "request_prompt", nullable = false, columnDefinition = "TEXT")
    private String requestPrompt;

    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(name = "html_content", nullable = false, columnDefinition = "TEXT")
    private String htmlContent;

    /*
     * LLM 이 HTML 과 함께 보낸 설명이다. 검증하지 않은 문장이고, 이 컬럼을 추가하기 전에 만든
     * 버전과 설명이 없던 응답은 null 이다.
     */
    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    private TemplateVersion(
            Template template, int versionNumber, String requestPrompt, String requestId, String htmlContent,
            String message) {
        if (versionNumber < 1) {
            throw new IllegalArgumentException("versionNumber must be positive");
        }
        this.template = template;
        this.versionNumber = versionNumber;
        this.requestPrompt = requiredText(requestPrompt, "requestPrompt");
        this.requestId = requiredText(requestId, "requestId");
        this.htmlContent = requiredText(htmlContent, "htmlContent");
        this.message = message;
    }

    static TemplateVersion create(
            Template template, int versionNumber, String requestPrompt, String requestId, String htmlContent) {
        return create(template, versionNumber, requestPrompt, requestId, htmlContent, null);
    }

    static TemplateVersion create(
            Template template, int versionNumber, String requestPrompt, String requestId, String htmlContent,
            String message) {
        return new TemplateVersion(template, versionNumber, requestPrompt, requestId, htmlContent, message);
    }

    private static String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
