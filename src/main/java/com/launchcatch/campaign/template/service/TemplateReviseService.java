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
import com.launchcatch.campaign.template.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.TemplateSlotValidator;
import com.launchcatch.campaign.template.TemplateStructureValidator;
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

    /** 템플릿 한 개가 보존하는 버전 수의 상한이다(#410). 넘기면 가장 오래된 것을 지운다. */
    private static final int MAX_VERSIONS_PER_TEMPLATE = 20;

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final TemplateHtmlGenerator templateHtmlGenerator;
    private final TemplateHtmlSanitizer templateHtmlSanitizer;
    private final TemplateSlotValidator templateSlotValidator;
    private final TemplateStructureValidator templateStructureValidator;
    private final TemplateGenerationRateLimiter templateGenerationRateLimiter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /*
     * requestId 로 먼저 조회해 재시도인지 본다. 재시도면 LLM 을 다시 부르지 않고
     * 그때 만든 버전으로 바로 응답한다. 찾은 버전이 이 요청의 templateId 와 다른
     * 템플릿 것이면 재시도가 아니라 requestId 가 잘못 재사용된 것이므로 거부한다.
     * LLM 호출은 트랜잭션 밖에서 한다(생성과 같은 이유). request_id 의 UNIQUE 충돌은
     * 저장 트랜잭션 밖에서 잡아 다시 조회한다(TemplateCreateService 에서 코드래빗
     * 리뷰로 고친 것과 같은 패턴).
     *
     * requestId 재시도가 아니라고 확인한 뒤에만 속도 제한을 센다(#227). 멱등성으로
     * 걸러지는 같은 요청의 재시도까지 관리자의 호출 횟수로 세면 안 된다. 저장되기 전에(=
     * LLM 호출이 끝나기 전에) 같은 requestId가 다시 들어오는 경우는 이 조회로 못 걸러서
     * checkLimit 안에서 requestId 기준으로 한 번만 세게 한다.
     */
    public TemplateCreateResponse revise(Long templateId, String requestPrompt, String requestId, Long adminId) {
        var existing = templateVersionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return toResponseForMatchingTemplate(existing.get(), templateId);
        }
        templateGenerationRateLimiter.checkLimit(adminId, requestId);
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
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
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
     * 같은 이유다: LLM 호출(재시도 1회까지 포함해 최대 60초) 동안 다른 트랜잭션이
     * 이 템플릿을 게시해 커밋했을 수 있는데, 잠금을 잡고 나서야 그 최신 상태를 안전하게 본다.
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
        /*
         * template(이미 id가 있는, "새 것"이 아닌 엔티티)을 저장소로 saveAndFlush 하면
         * JpaRepository가 merge()로 보낸다. merge가 versions 컬렉션의 cascade=PERSIST를
         * 타면서 IDENTITY 생성 전략과 얽혀, version을 "먼저 빈 값으로 INSERT 하고 나중에
         * UPDATE" 하는 순서로 처리된다(#433 재현으로 확인). html_content가 NOT NULL이라
         * 그 첫 INSERT에서 바로 제약 위반이 난다. version은 새 엔티티(id 없음)라 그
         * 저장소로 직접 저장하면 persist() 경로를 타 이 문제가 없다. template의
         * lastModifiedBy/At 변경은 managed 상태라 이 flush에 같이 반영된다.
         */
        templateVersionRepository.saveAndFlush(version);
        evictVersionsOverCap(templateId, nextVersionNumber);
        return toResponse(version, sanitized.removedElements());
    }

    /*
     * 버전은 템플릿당 MAX_VERSIONS_PER_TEMPLATE 개까지만 보존한다(#410). 넘긴 만큼을
     * 한 번에 소프트삭제한다. 번호는 건드리지 않는다.
     *
     * 가장 오래된 것 하나만 지우지 않는다(코드래빗 리뷰로 찾음). 이 기능이 배포되기
     * 전부터 상한보다 훨씬 많이 쌓여 있던 템플릿은, 하나만 지우면 수정할 때마다 하나
     * 늘고 하나만 줄어 영원히 상한을 넘긴 채로 남는다. thresholdVersionNumber 이하인
     * 살아있는 버전을 전부 지우면 몇 개가 넘쳤든 그 즉시 20개로 돌아온다.
     *
     * 번호를 앞으로 당기는 방식(재배치)도 쓰지 않는다. TemplatePublishRequest.
     * versionNumber(관리자가 직접 지정)와 버전 목록의 커서 페이지네이션이 번호를 고정
     * 식별자로 쓰고 있어서, 번호가 밀리면 그 사이 다른 내용을 가리키게 된다.
     *
     * save()와 같은 잠금(findByIdForUpdate) 안에서 돌아, 버전 추가와 오래된 버전 삭제가
     * 같은 템플릿을 동시에 수정하는 다른 요청과 경쟁하지 않는다.
     */
    private void evictVersionsOverCap(Long templateId, int newestVersionNumber) {
        int thresholdVersionNumber = newestVersionNumber - MAX_VERSIONS_PER_TEMPLATE;
        templateVersionRepository.softDeleteUpToVersionNumber(templateId, thresholdVersionNumber, LocalDateTime.now(clock));
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
                removedElements,
                TemplateCreateResponse.SUGGESTED_PROMPTS);
    }
}
