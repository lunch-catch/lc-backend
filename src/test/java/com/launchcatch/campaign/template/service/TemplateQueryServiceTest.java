package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class TemplateQueryServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long TEMPLATE_ID = 1L;
    private static final String PUBLISHED_HTML = "<div data-slot=\"eventName\">published</div>";

    @Mock
    private TemplateRepository templateRepository;
    @Mock
    private TemplateVersionRepository templateVersionRepository;

    private TemplateQueryService service;

    @BeforeEach
    void setUp() {
        service = new TemplateQueryService(templateRepository, templateVersionRepository);
    }

    @Test
    @DisplayName("목록은 저장소가 돌려준 순서 그대로 응답으로 바꾸고, 상태에 맞는 미리보기 html을 채운다")
    void 목록을_조회한다() throws Exception {
        Template draft = draftTemplate(1L, "가을 신메뉴");
        Template published = publishedTemplate(2L);
        when(templateRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(published, draft));
        when(templateVersionRepository.findLatestAliveByTemplateIds(List.of(1L)))
                .thenReturn(List.of(draft.latestVersion()));

        List<TemplateListItemResponse> responses = service.list();

        assertThat(responses).extracting(TemplateListItemResponse::templateId)
                .containsExactly(2L, 1L);
        assertThat(responses.get(0).html()).isEqualTo(PUBLISHED_HTML);
        assertThat(responses.get(1).html()).isEqualTo(draft.latestVersion().getHtmlContent());
    }

    @Test
    @DisplayName("목록은 DRAFT 템플릿이 여럿이어도 최신 버전을 한 번에 조회한다(N+1 방지, #532)")
    void 목록은_최신_버전을_한번에_조회한다() throws Exception {
        Template first = draftTemplate(1L, "가을 신메뉴");
        Template second = draftTemplate(3L, "겨울 신메뉴");
        when(templateRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(second, first));
        when(templateVersionRepository.findLatestAliveByTemplateIds(List.of(3L, 1L)))
                .thenReturn(List.of(first.latestVersion(), second.latestVersion()));

        List<TemplateListItemResponse> responses = service.list();

        assertThat(responses).extracting(TemplateListItemResponse::html)
                .containsExactly("html-3", "html-1");
        verify(templateVersionRepository, times(1)).findLatestAliveByTemplateIds(any());
        verify(templateVersionRepository, never())
                .findFirstByTemplateIdAndDeletedAtIsNullOrderByVersionNumberDesc(any());
    }

    @Test
    @DisplayName("게시된 템플릿만 있으면 최신 버전을 조회하지 않는다")
    void 게시된_템플릿만_있으면_버전을_조회하지_않는다() throws Exception {
        when(templateRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(publishedTemplate(2L)));

        List<TemplateListItemResponse> responses = service.list();

        assertThat(responses.get(0).html()).isEqualTo(PUBLISHED_HTML);
        verify(templateVersionRepository, never()).findLatestAliveByTemplateIds(any());
    }

    @Test
    @DisplayName("DRAFT 템플릿에 살아있는 버전이 없으면 불변식 위반으로 예외를 던진다")
    void 목록에서_버전이_없으면_예외를_던진다() throws Exception {
        Template draft = draftTemplate(1L, "가을 신메뉴");
        when(templateRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(draft));
        when(templateVersionRepository.findLatestAliveByTemplateIds(List.of(1L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.list()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("점주 목록은 게시되고 활성인 템플릿만 templateId/name/html로 돌려준다")
    void 점주_목록을_조회한다() throws Exception {
        Template active = publishedTemplate(2L);
        active.changeActive(true, ADMIN_ID, LocalDateTime.now());
        when(templateRepository.findAllByStatusAndActiveTrueOrderByCreatedAtDesc(TemplateStatus.PUBLISHED))
                .thenReturn(List.of(active));

        List<OwnerTemplateListItemResponse> responses = service.listForOwner();

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).templateId()).isEqualTo(2L);
        assertThat(responses.get(0).name()).isEqualTo(active.getName());
        assertThat(responses.get(0).html()).isEqualTo(PUBLISHED_HTML);
    }

    @Test
    @DisplayName("점주 목록에서 활성 템플릿이 없으면 빈 배열이다")
    void 점주_목록이_비면_빈_배열이다() {
        when(templateRepository.findAllByStatusAndActiveTrueOrderByCreatedAtDesc(TemplateStatus.PUBLISHED))
                .thenReturn(List.of());

        List<OwnerTemplateListItemResponse> responses = service.listForOwner();

        assertThat(responses).isEmpty();
    }

    @Test
    @DisplayName("DRAFT 템플릿 상세는 최신 버전의 html을 돌려준다")
    void DRAFT_상세는_최신_버전_html_이다() throws Exception {
        Template template = draftTemplate(TEMPLATE_ID, "가을 신메뉴");
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findFirstByTemplateIdAndDeletedAtIsNullOrderByVersionNumberDesc(TEMPLATE_ID))
                .thenReturn(Optional.of(template.latestVersion()));

        TemplateDetailResponse response = service.detail(TEMPLATE_ID);

        assertThat(response.html()).isEqualTo(template.latestVersion().getHtmlContent());
    }

    @Test
    @DisplayName("게시된 템플릿 상세는 Template.htmlContent 를 돌려준다")
    void 게시된_템플릿_상세는_htmlContent_이다() throws Exception {
        Template template = publishedTemplate(TEMPLATE_ID);
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        TemplateDetailResponse response = service.detail(TEMPLATE_ID);

        assertThat(response.html()).isEqualTo(PUBLISHED_HTML);
    }

    @Test
    @DisplayName("상세 조회에서 템플릿이 없으면 POSTER-005 로 거부한다")
    void 상세_조회에서_템플릿이_없으면_거부한다() {
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(TEMPLATE_ID))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("DRAFT 템플릿에 버전이 하나도 없으면(불변식 위반) 예외를 던진다")
    void DRAFT_인데_버전이_없으면_예외를_던진다() throws Exception {
        Template template = draftTemplate(TEMPLATE_ID, "가을 신메뉴");
        when(templateRepository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateVersionRepository.findFirstByTemplateIdAndDeletedAtIsNullOrderByVersionNumberDesc(TEMPLATE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(TEMPLATE_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("버전 목록 조회에서 템플릿이 없으면 POSTER-005 로 거부한다")
    void 버전_목록에서_템플릿이_없으면_거부한다() {
        when(templateRepository.existsById(TEMPLATE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.versions(TEMPLATE_ID, null, 20))
                .isInstanceOfSatisfying(CampaignException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(PosterErrorCode.TEMPLATE_NOT_FOUND));
    }

    @Test
    @DisplayName("다음 페이지가 없으면 요청한 개수 이하로 돌려주고 nextPageToken 은 null 이다")
    void 마지막_페이지는_다음_토큰이_없다() throws Exception {
        when(templateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        Template template = draftTemplate(TEMPLATE_ID, "가을 신메뉴");
        when(templateVersionRepository.findPage(eq(TEMPLATE_ID), isNull(), any(Pageable.class)))
                .thenReturn(List.of(template.latestVersion()));

        CursorPageResponse<TemplateVersionItemResponse> response = service.versions(TEMPLATE_ID, null, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.nextPageToken()).isNull();
    }

    @Test
    @DisplayName("다음 페이지가 더 있으면 요청한 개수만 자르고 nextPageToken 을 채운다")
    void 다음_페이지가_있으면_잘라서_토큰을_채운다() throws Exception {
        when(templateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        Template template = draftTemplate(TEMPLATE_ID, "가을 신메뉴");
        TemplateVersion v1 = template.latestVersion();
        TemplateVersion v2 = template.addDraftVersion(ADMIN_ID, LocalDateTime.now(), "수정", "req-2", "html2", 2);
        setVersionId(v2, 2L);
        when(templateVersionRepository.findPage(eq(TEMPLATE_ID), isNull(), any(Pageable.class)))
                .thenReturn(List.of(v2, v1));

        CursorPageResponse<TemplateVersionItemResponse> response = service.versions(TEMPLATE_ID, null, 1);

        assertThat(response.items()).extracting(TemplateVersionItemResponse::versionNumber)
                .containsExactly(2);
        assertThat(response.nextPageToken()).isNotNull();
        PageCursor decoded = PageTokens.decode(response.nextPageToken());
        assertThat(decoded.sortValue()).isEqualTo("2");
    }

    @Test
    @DisplayName("pageToken 이 있으면 디코딩한 버전 번호를 커서로 넘긴다")
    void pageToken_을_디코딩해서_커서로_넘긴다() throws Exception {
        when(templateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        String token = PageTokens.encode(new PageCursor(5L, "3"));
        when(templateVersionRepository.findPage(eq(TEMPLATE_ID), eq(3), any(Pageable.class)))
                .thenReturn(List.of());

        service.versions(TEMPLATE_ID, token, 20);

        verify(templateVersionRepository).findPage(eq(TEMPLATE_ID), eq(3), any(Pageable.class));
    }

    @Test
    @DisplayName("pageToken 의 sortValue 가 없으면(null) 예외 없이 첫 페이지로 본다")
    void pageToken_sortValue_가_없으면_첫_페이지로_본다() {
        when(templateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        String token = PageTokens.encode(new PageCursor(5L, null));
        when(templateVersionRepository.findPage(eq(TEMPLATE_ID), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        service.versions(TEMPLATE_ID, token, 20);

        verify(templateVersionRepository).findPage(eq(TEMPLATE_ID), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("pageToken 의 sortValue 가 숫자가 아니면 예외 없이 첫 페이지로 본다")
    void pageToken_sortValue_가_숫자가_아니면_첫_페이지로_본다() {
        when(templateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        String token = PageTokens.encode(new PageCursor(5L, "notanumber"));
        when(templateVersionRepository.findPage(eq(TEMPLATE_ID), isNull(), any(Pageable.class)))
                .thenReturn(List.of());

        service.versions(TEMPLATE_ID, token, 20);

        verify(templateVersionRepository).findPage(eq(TEMPLATE_ID), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("pageSize 가 0 이하면 기본값을 쓰고, 최대치를 넘으면 최대치로 자른다")
    void pageSize_를_보정한다() throws Exception {
        when(templateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        when(templateVersionRepository.findPage(eq(TEMPLATE_ID), isNull(), any(Pageable.class)))
                .thenReturn(List.of());
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        service.versions(TEMPLATE_ID, null, 0);
        service.versions(TEMPLATE_ID, null, 1000);

        verify(templateVersionRepository, org.mockito.Mockito.times(2))
                .findPage(eq(TEMPLATE_ID), isNull(), pageableCaptor.capture());
        List<Pageable> pageables = pageableCaptor.getAllValues();
        assertThat(pageables.get(0).getPageSize()).isEqualTo(21);
        assertThat(pageables.get(1).getPageSize()).isEqualTo(101);
    }

    private Template draftTemplate(Long templateId, String name) throws Exception {
        Template template = Template.createDraft(name);
        setId(template, templateId);
        template.addDraftVersion(ADMIN_ID, LocalDateTime.now(), "처음 요청", "req-" + templateId, "html-" + templateId, 1);
        setVersionId(template.latestVersion(), templateId);
        return template;
    }

    private Template publishedTemplate(Long templateId) throws Exception {
        Template template = draftTemplate(templateId, "가을 신메뉴");
        setStatus(template, TemplateStatus.PUBLISHED);
        setHtmlContent(template, PUBLISHED_HTML);
        return template;
    }

    private void setId(Object entity, Long id) throws Exception {
        Field field = BaseTimeEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private void setVersionId(TemplateVersion version, Long id) throws Exception {
        setId(version, id);
    }

    private void setStatus(Template template, TemplateStatus status) throws Exception {
        Field field = Template.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(template, status);
    }

    private void setHtmlContent(Template template, String html) throws Exception {
        Field field = Template.class.getDeclaredField("htmlContent");
        field.setAccessible(true);
        field.set(template, html);
    }
}
