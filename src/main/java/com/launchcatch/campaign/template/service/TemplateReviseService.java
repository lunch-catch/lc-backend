package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.client.TemplateGenerationTimeoutException;
import com.launchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class TemplateReviseService {

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final TemplateHtmlGenerator templateHtmlGenerator;
    private final TemplateHtmlSanitizer templateHtmlSanitizer;
    private final TemplateSlotValidator templateSlotValidator;
    private final TemplateStructureValidator templateStructureValidator;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /*
     * requestId 로 먼저 조회해 재시도인지 본다. 재시도면 LLM 을 다시 부르지 않고
     * 그때 만든 버전으로 바로 응답한다. 찾은 버전이 이 요청의 templateId 와 다른
     * 템플릿 것이면 재시도가 아니라 requestId 가 잘못 재사용된 것이므로 거부한다.
     * LLM 호출은 트랜잭션 밖에서 한다(생성과 같은 이유). request_id 의 UNIQUE 충돌은
     * 저장 트랜잭션 밖에서 잡아 다시 조회한다(TemplateCreateService 에서 코드래빗
     * 리뷰로 고친 것과 같은 패턴).
     */
    public TemplateCreateResponse revise(Long templateId, String requestPrompt, String requestId, Long adminId) {
        var existing = templateVersionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return toResponseForMatchingTemplate(existing.get(), templateId);
        }
        findDraftTemplate(templateId);
        String previousHtml = latestVersionHtml(templateId);
        Document previousDocument = Jsoup.parseBodyFragment(previousHtml);
        Document document = Jsoup.parseBodyFragment(generate(previousHtml, requestPrompt));
        TemplateSanitizeResult sanitized = templateHtmlSanitizer.sanitize(document);
        templateSlotValidator.validate(document);
        templateStructureValidator.validate(document, previousDocument);
        try {
            return transactionTemplate.execute(
                    status -> save(templateId, requestPrompt, requestId, adminId, sanitized));
        } catch (DataIntegrityViolationException e) {
            return templateVersionRepository.findByRequestId(requestId)
                    .map(found -> toResponseForMatchingTemplate(found, templateId))
                    .orElseThrow(() -> e);
        }
    }

    /*
     * 버전을 전혀 안 가져온다. 존재/상태 확인에는 필요 없다.
     */
    private Template findDraftTemplate(Long templateId) {
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() != TemplateStatus.DRAFT) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_DRAFT);
        }
        return template;
    }

    /*
     * LLM 에 넘길 최신 버전 HTML 하나만 가져온다. 이 템플릿의 과거 버전들은 안 건든다.
     */
    private String latestVersionHtml(Long templateId) {
        return templateVersionRepository.findFirstByTemplateIdOrderByVersionNumberDesc(templateId)
                .map(TemplateVersion::getHtmlContent)
                .orElseThrow(() -> new IllegalStateException("template has no version: id=" + templateId));
    }

    private String generate(String previousHtml, String requestPrompt) {
        try {
            return templateHtmlGenerator.revise(previousHtml, requestPrompt);
        } catch (TemplateGenerationTimeoutException e) {
            throw new CampaignException(PosterErrorCode.GENERATION_TIMEOUT, e);
        }
    }

    /*
     * 저장은 트랜잭션 안에서 템플릿을 다시 조회해 쓴다. 트랜잭션 밖에서 읽은
     * detached 엔티티를 그대로 merge 하지 않고, 매번 managed 상태로 새로 가져온다.
     *
     * findByIdForUpdate 로 행 잠금을 걸고 가져온다. 같은 템플릿을 동시에 수정하는
     * 두 트랜잭션 중 하나가 여기서 기다리게 돼, 둘 다 같은 "다음 버전 번호"를
     * 계산해 request_id 가 다른데도 (template_id, version_number) UNIQUE 제약에
     * 부딪히는 경쟁이 없어진다. 잠금을 잡은 뒤 DRAFT 상태를 다시 확인하는 것도
     * 같은 이유다: LLM 호출(최대 30초) 동안 다른 트랜잭션이 이 템플릿을 게시해
     * 커밋했을 수 있는데, 잠금을 잡고 나서야 그 최신 상태를 안전하게 본다.
     */
    private TemplateCreateResponse save(
            Long templateId, String requestPrompt, String requestId, Long adminId, TemplateSanitizeResult sanitized) {
        Template template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() != TemplateStatus.DRAFT) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_DRAFT);
        }
        int nextVersionNumber = templateVersionRepository.findMaxVersionNumber(templateId) + 1;
        TemplateVersion version = template.addDraftVersion(
                adminId, LocalDateTime.now(clock), requestPrompt, requestId, sanitized.html(), nextVersionNumber);
        templateRepository.saveAndFlush(template);
        return toResponse(version, sanitized.removedElements());
    }

    /*
     * requestId 로 찾은 버전이 이 요청의 templateId 소속이 맞는지 확인한다. 같은
     * requestId 가 다른 templateId 로 재사용되면(클라이언트 버그 또는 악의적 재사용)
     * 엉뚱한 템플릿의 버전을 돌려주게 되므로, 다르면 이 templateId 로는 해당 버전이
     * 없는 것으로 보고 거부한다.
     */
    private TemplateCreateResponse toResponseForMatchingTemplate(TemplateVersion version, Long templateId) {
        if (!version.getTemplate().getId().equals(templateId)) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND);
        }
        return toResponse(version, List.of());
    }

    /*
     * 재시도로 되돌려주는 응답은 removedElements 를 비워 둔다. 그 목록은 저장하지 않아
     * 처음 응답 이후로는 다시 만들어낼 수 없다.
     */
    private TemplateCreateResponse toResponse(TemplateVersion version, List<String> removedElements) {
        Template template = version.getTemplate();
        return new TemplateCreateResponse(
                template.getId(),
                template.getName(),
                version.getVersionNumber(),
                template.getStatus(),
                version.getHtmlContent(),
                removedElements);
    }
}
