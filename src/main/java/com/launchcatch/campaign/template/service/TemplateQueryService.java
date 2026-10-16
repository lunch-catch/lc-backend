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
     * DRAFT 템플릿마다 htmlOf() 가 최신 버전을 따로 조회해 N+1 이다. 템플릿이 최대 10개로
     * 제한돼 있어(TemplateQuota) 지금은 목록 조회 1번에 최대 11번 쿼리로 끝나지만, 그 제한이
     * 풀리면 템플릿 수에 비례해 느려진다. 제한을 풀 때는 이 메서드도 같이 봐야 한다.
     */
    public List<TemplateListItemResponse> list() {
        return templateRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(template -> TemplateListItemResponse.of(template, htmlOf(template)))
                .toList();
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
