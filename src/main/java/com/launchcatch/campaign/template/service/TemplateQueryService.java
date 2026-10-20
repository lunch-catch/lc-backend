package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.OwnerTemplateListItemResponse;
import com.launchcatch.campaign.template.dto.TemplateDetailResponse;
import com.launchcatch.campaign.template.dto.TemplateListItemResponse;
import com.launchcatch.campaign.template.dto.TemplateVersionItemResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;

    /*
     * DRAFT 템플릿의 미리보기 html 은 최신 버전들을 한 번에 조회해(findLatestAliveByTemplateIds)
     * 채운다. 템플릿마다 htmlOf() 로 따로 조회하면 템플릿 수만큼 쿼리가 나가는 N+1 이라, 상한이
     * 100개가 된 지금은 목록 한 번에 쿼리가 최대 101번 나간다(#532). 쿼리는 템플릿 수와 무관하게
     * 템플릿 목록 1번, 최신 버전 1번이다.
     */
    public List<TemplateListItemResponse> list() {
        List<Template> templates = templateRepository.findAllByOrderByCreatedAtDesc();
        List<Long> draftIds = templates.stream()
                .filter(template -> template.getStatus() != TemplateStatus.PUBLISHED)
                .map(Template::getId)
                .toList();
        Map<Long, String> latestHtml = draftIds.isEmpty()
                ? Map.of()
                : templateVersionRepository.findLatestAliveByTemplateIds(draftIds).stream()
                        .collect(Collectors.toMap(
                                version -> version.getTemplate().getId(), TemplateVersion::getHtmlContent));
        return templates.stream()
                .map(template -> TemplateListItemResponse.of(template, previewHtml(template, latestHtml)))
                .toList();
    }

    private String previewHtml(Template template, Map<Long, String> latestHtml) {
        if (template.getStatus() == TemplateStatus.PUBLISHED) {
            return template.getHtmlContent();
        }
        String html = latestHtml.get(template.getId());
        if (html == null) {
            throw new IllegalStateException("template has no version: id=" + template.getId());
        }
        return html;
    }

    /*
     * 게시되고 활성인 템플릿만 대상이라 htmlOf() 의 DRAFT 분기가 필요 없다.
     * Template.htmlContent 를 바로 쓴다.
     */
    public List<OwnerTemplateListItemResponse> listForOwner() {
        return templateRepository.findAllByStatusAndActiveTrueOrderByCreatedAtDesc(TemplateStatus.PUBLISHED).stream()
                .map(OwnerTemplateListItemResponse::of)
                .toList();
    }

    public TemplateDetailResponse detail(Long templateId) {
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        return TemplateDetailResponse.of(template, htmlOf(template));
    }

    public CursorPageResponse<TemplateVersionItemResponse> versions(Long templateId, String pageToken, int pageSize) {
        if (!templateRepository.existsById(templateId)) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND);
        }
        int limit = clampPageSize(pageSize);
        Integer cursorVersionNumber = decodeCursorVersionNumber(pageToken);
        Pageable pageable = PageRequest.of(0, limit + 1);

        List<TemplateVersion> fetched =
                templateVersionRepository.findPage(templateId, cursorVersionNumber, pageable);
        boolean hasNext = fetched.size() > limit;
        List<TemplateVersion> page = hasNext ? fetched.subList(0, limit) : fetched;

        List<TemplateVersionItemResponse> items = page.stream()
                .map(TemplateVersionItemResponse::from)
                .toList();
        String nextPageToken = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
        return CursorPageResponse.of(items, nextPageToken);
    }

    /*
     * 게시된 템플릿은 Template.htmlContent 에 게시 시점의 HTML 이 복사돼 있고,
     * DRAFT 템플릿은 그 칼럼이 비어 있어 최신 버전에서 가져온다.
     */
    private String htmlOf(Template template) {
        if (template.getStatus() == TemplateStatus.PUBLISHED) {
            return template.getHtmlContent();
        }
        return templateVersionRepository.findFirstByTemplateIdAndDeletedAtIsNullOrderByVersionNumberDesc(template.getId())
                .map(TemplateVersion::getHtmlContent)
                .orElseThrow(() -> new IllegalStateException("template has no version: id=" + template.getId()));
    }

    private int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /*
     * PageTokens.decode() 는 형식이 어긋난 토큰을 null 로 돌려줘 첫 페이지로 본다.
     * sortValue 는 PageTokens 가 뜻을 모르는 임의 문자열이라 그 경계를 넘지 못한다.
     * 그래서 versionNumber 로 바꾸지 못하는 값(없거나 숫자가 아님)도 같은 규칙으로
     * 첫 페이지로 본다. 그렇지 않으면 위조되거나 깨진 토큰이 500 으로 샌다.
     */
    private Integer decodeCursorVersionNumber(String pageToken) {
        PageCursor cursor = PageTokens.decode(pageToken);
        if (cursor == null || cursor.sortValue() == null) {
            return null;
        }
        try {
            return Integer.valueOf(cursor.sortValue());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String encodeCursor(TemplateVersion lastItem) {
        return PageTokens.encode(new PageCursor(lastItem.getId(), String.valueOf(lastItem.getVersionNumber())));
    }
}
