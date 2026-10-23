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

    @Column(name = "published_version_number")
    private Integer publishedVersionNumber;

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
        return addDraftVersion(adminId, now, requestPrompt, requestId, htmlContent, null, versionNumber);
    }

    public TemplateVersion addDraftVersion(
            Long adminId, LocalDateTime now, String requestPrompt, String requestId, String htmlContent,
            String message, int versionNumber) {
        TemplateVersion version =
                TemplateVersion.create(this, versionNumber, requestPrompt, requestId, htmlContent, message);
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

    /*
     * status와 publishedAt, publishedVersionNumber를 항상 같이 바꾼다. DB의
     * chk_template_status 제약이 세 값을 함께 요구하고 있어서, 하나만 바꾸면
     * 커밋 시점에 제약 위반으로 터진다. publishedVersionNumber는 게시 재시도를
     * 가려내는 값이라(TemplatePublishService) 게시할 때마다 반드시 같이 남긴다.
     */
    public void publish(String htmlContent, LocalDateTime now, int versionNumber) {
        if (this.status != TemplateStatus.DRAFT) {
            throw new IllegalStateException("only DRAFT template can be published: " + this.status);
        }
        this.htmlContent = htmlContent;
        this.status = TemplateStatus.PUBLISHED;
        this.publishedAt = now;
        this.publishedVersionNumber = versionNumber;
    }

    /*
     * 게시된 템플릿을 임시저장 버전으로 다시 게시한다(#880). 게시본 HTML 과 게시 시각, 게시 버전 번호, 마지막
     * 수정자를 바꾸고 active 는 건드리지 않는다. status, publishedAt, publishedVersionNumber 를 함께 갱신하는 이유는
     * publish() 와 같다.
     */
    public void republish(String htmlContent, LocalDateTime now, int versionNumber, Long adminId) {
        if (this.status != TemplateStatus.PUBLISHED) {
            throw new IllegalStateException("only PUBLISHED template can be republished: " + this.status);
        }
        this.htmlContent = htmlContent;
        this.publishedAt = now;
        this.publishedVersionNumber = versionNumber;
        this.lastModifiedBy = adminId;
        this.lastModifiedAt = now;
    }

    // 상태와 무관하게 이름을 바꾼다(#880). 이미 만든 포스터의 제목은 만들 때 정해져 바뀌지 않는다.
    public void rename(String name, Long adminId, LocalDateTime now) {
        this.name = requiredName(name);
        this.lastModifiedBy = adminId;
        this.lastModifiedAt = now;
    }

    public void changeActive(boolean active, Long adminId, LocalDateTime now) {
        if (this.status != TemplateStatus.PUBLISHED) {
            throw new IllegalStateException("only PUBLISHED template can change active: " + this.status);
        }
        this.active = active;
        this.activatedBy = adminId;
        this.activatedAt = now;
    }

    /*
     * 앞뒤 공백을 자른 값을 저장한다(#880). 이름은 포스터 제목 "{가게명} - {템플릿 이름}" 에 그대로 들어가서, 공백이
     * 섞이면 제목에도 섞인다. 생성과 이름 수정이 이 한 곳을 거치므로 같은 규칙이 적용된다.
     */
    private static String requiredName(String value) {
        String name = value == null ? null : value.strip();
        if (name == null || name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("name must be 1 to " + NAME_MAX_LENGTH + " characters");
        }
        return name;
    }
}
