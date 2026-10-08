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

    public TemplateVersion addDraftVersion(Long adminId, LocalDateTime now, String requestPrompt, String htmlContent) {
        TemplateVersion version = TemplateVersion.create(this, nextVersionNumber(), requestPrompt, htmlContent);
        versions.add(version);
        this.lastModifiedBy = adminId;
        this.lastModifiedAt = now;
        return version;
    }

    private int nextVersionNumber() {
        return versions.stream().mapToInt(TemplateVersion::getVersionNumber).max().orElse(0) + 1;
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank() || value.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("name must be 1 to " + NAME_MAX_LENGTH + " characters");
        }
        return value;
    }
}
