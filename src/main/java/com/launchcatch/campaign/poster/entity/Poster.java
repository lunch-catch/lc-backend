package com.launchcatch.campaign.poster.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "poster")
@AttributeOverride(name = "id", column = @Column(name = "poster_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Poster extends BaseTimeEntity {

    // title 컬럼의 한도다. 포스터를 만들 때 제목을 자르는 PosterUploadService 도 이 값을 쓴다(#658).
    public static final int TITLE_MAX_LENGTH = 100;

    @Column(name = "campaign_id", nullable = false, unique = true)
    private Long campaignId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    // {슬롯키: {type, value}} 형태의 JSON 문자열이다. PosterUploadService가 ObjectMapper로 만든다.
    @Column(name = "slot_values", columnDefinition = "json", nullable = false)
    private String slotValues;

    @Column(name = "html_content", columnDefinition = "TEXT", nullable = false)
    private String htmlContent;

    @Column(name = "image_object_key", nullable = false)
    private String imageObjectKey;

    private Poster(
            Long campaignId, Long templateId, String title, String slotValues, String htmlContent,
            String imageObjectKey) {
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.title = requiredTitle(title);
        this.slotValues = Objects.requireNonNull(slotValues, "slotValues");
        this.htmlContent = Objects.requireNonNull(htmlContent, "htmlContent");
        this.imageObjectKey = Objects.requireNonNull(imageObjectKey, "imageObjectKey");
    }

    public static Poster create(
            Long campaignId, Long templateId, String title, String slotValues, String htmlContent,
            String imageObjectKey) {
        return new Poster(campaignId, templateId, title, slotValues, htmlContent, imageObjectKey);
    }

    // 캠페인당 포스터는 1건이라 다시 생성을 요청하면 새로 만들지 않고 이 필드들을 덮어쓴다.
    public void overwrite(Long templateId, String title, String slotValues, String htmlContent,
            String imageObjectKey) {
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.title = requiredTitle(title);
        this.slotValues = Objects.requireNonNull(slotValues, "slotValues");
        this.htmlContent = Objects.requireNonNull(htmlContent, "htmlContent");
        this.imageObjectKey = Objects.requireNonNull(imageObjectKey, "imageObjectKey");
    }

    private static String requiredTitle(String value) {
        if (value == null || value.isBlank() || value.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("title must be 1 to " + TITLE_MAX_LENGTH + " characters");
        }
        return value;
    }
}
