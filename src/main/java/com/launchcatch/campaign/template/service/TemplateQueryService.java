package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
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

    public List<TemplateListItemResponse> list() {
        return templateRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(template -> TemplateListItemResponse.of(template, htmlOf(template)))
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
        return templateVersionRepository.findFirstByTemplateIdOrderByVersionNumberDesc(template.getId())
                .map(TemplateVersion::getHtmlContent)
                .orElseThrow(() -> new IllegalStateException("template has no version: id=" + template.getId()));
    }

    private int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private Integer decodeCursorVersionNumber(String pageToken) {
        PageCursor cursor = PageTokens.decode(pageToken);
        return cursor == null ? null : Integer.valueOf(cursor.sortValue());
    }

    private String encodeCursor(TemplateVersion lastItem) {
        return PageTokens.encode(new PageCursor(lastItem.getId(), String.valueOf(lastItem.getVersionNumber())));
    }
}
