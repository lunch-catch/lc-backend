package com.launchcatch.campaign.template.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "template")
@AttributeOverride(name = "id", column = @Column(name = "template_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Template extends BaseTimeEntity {

    private static final int NAME_MAX_LENGTH = 100;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TemplateStatus status;

    /*
     * 게시 전까지 비어 있다.
     * 게시할 때 선택한 버전의 HTML 이 여기 복사된다.
     */
    @Column(name = "html_content", columnDefinition = "TEXT")
    private String htmlContent;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "activated_by")
    private Long activatedBy;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "last_modified_by")
    private Long lastModifiedBy;

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;

    @Getter(AccessLevel.NONE)
    @OneToMany(mappedBy = "template", cascade = CascadeType.PERSIST)
    private List<TemplateVersion> versions = new ArrayList<>();

    private Template(String name) {
        this.name = requiredName(name);
        this.status = TemplateStatus.DRAFT;
        this.active = false;
    }

    public static Template createDraft(String name) {
        return new Template(name);
    }

    /*
     * versionNumber 는 호출하는 쪽이 넘긴다. 이 템플릿의 versions 가 항상 전부
     * 로딩돼 있다고 가정할 수 없어서(가벼운 조회로는 일부만 가져올 수 있음),
     * "다음 번호"를 이 안에서 versions 를 훑어 계산하지 않는다.
     */
    public TemplateVersion addDraftVersion(
            Long adminId, LocalDateTime now, String requestPrompt, String requestId, String htmlContent,
            int versionNumber) {
        TemplateVersion version =
                TemplateVersion.create(this, versionNumber, requestPrompt, requestId, htmlContent);
        versions.add(version);
        this.lastModifiedBy = adminId;
        this.lastModifiedAt = now;
        return version;
    }

    /*
     * createDraft() 직후 바로 addDraftVersion() 이 불려 버전이 최소 1개는 항상 있다.
     * 그래서 없는 경우는 불변식이 깨진 것으로 보고 예외를 던진다.
     */
    public TemplateVersion latestVersion() {
        return versions.stream()
                .max(Comparator.comparingInt(TemplateVersion::getVersionNumber))
                .orElseThrow(() -> new IllegalStateException("template has no version: id=" + getId()));
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank() || value.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("name must be 1 to " + NAME_MAX_LENGTH + " characters");
        }
        return value;
    }
}
