package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.client.GeneratedTemplate;
import com.launchcatch.campaign.template.client.TemplateGenerationTimeoutException;
import com.launchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateQuotaRepository;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import com.launchcatch.campaign.template.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.TemplatePromptValidator;
import com.launchcatch.campaign.template.TemplateSlotValidator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class TemplateCreateService {

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final TemplateQuotaRepository templateQuotaRepository;
    private final TemplateHtmlGenerator templateHtmlGenerator;
    private final TemplateHtmlSanitizer templateHtmlSanitizer;
    private final TemplateSlotValidator templateSlotValidator;
    private final TemplatePromptValidator templatePromptValidator;
    private final TemplateGenerationRateLimiter templateGenerationRateLimiter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // 실제 설계값은 10이다. campaign.template-max-count 의 주석(application.yml)이 그 근거다.
    @Value("${campaign.template-max-count}")
    private int maxTemplates;

    /*
     * requestId 로 먼저 조회해 재시도인지 본다. 재시도면 LLM 을 다시 부르지 않고 그때 만든
     * 버전으로 바로 응답한다. LLM 호출은 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB
     * 연결을 잡지 않으려는 것이다. 저장만 트랜잭션으로 묶는다. 여기서의 개수 확인은
     * LLM을 부르지 않으려는 빠른 실패용일 뿐이고, 진짜 안전장치는 save() 안의 조건부
     * UPDATE 와 request_id 의 UNIQUE 제약이다.
     *
     * requestId 재시도가 아니라고 확인한 뒤에만 속도 제한을 센다(#227). 멱등성으로
     * 걸러지는 같은 요청의 재시도까지 관리자의 호출 횟수로 세면 안 된다. 저장되기 전에(=
     * LLM 호출이 끝나기 전에) 같은 requestId가 다시 들어오는 경우는 이 조회로 못 걸러서
     * checkLimit 안에서 requestId 기준으로 한 번만 세게 한다.
     */
    public TemplateCreateResponse create(String name, String requestPrompt, String requestId, Long adminId) {
        var existing = templateVersionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return toResponse(existing.get(), List.of(), List.of());
        }
        templateGenerationRateLimiter.checkLimit(adminId, requestId);
        validatePrompt(requestPrompt, requestId);
        if (templateRepository.count() >= maxTemplates) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        GeneratedTemplate generated = generate(requestPrompt);
        if (generated.chatOnly()) {
            return chatOnlyResponse(requestId, generated);
        }
        Document document = Jsoup.parseBodyFragment(generated.html());
        TemplateSanitizeResult sanitized = templateHtmlSanitizer.sanitize(document);
        templateSlotValidator.validate(document);
        try {
            return transactionTemplate.execute(status -> save(name, requestPrompt, requestId, adminId, sanitized, generated.message(), generated.suggestedPrompts()));
        } catch (DataIntegrityViolationException e) {
            return templateVersionRepository.findByRequestId(requestId)
                    .map(found -> toResponse(found, List.<String>of(), List.<String>of()))
                    .orElseThrow(() -> e);
        }
    }

    // 요청 문구를 모델에 보내기 전에 거른다(#428). 거부해도 분당 3회에는 이미 센 상태다.
    private void validatePrompt(String requestPrompt, String requestId) {
        try {
            templatePromptValidator.validate(requestPrompt);
        } catch (CampaignException e) {
            templateGenerationRateLimiter.releaseInFlight(requestId);
            throw e;
        }
    }

    /*
     * 템플릿을 만들거나 고치라는 요청이 아니어서 모델이 자연어로만 답한 경우다(#733). 템플릿, 버전, template_quota 를
     * 만들지 않고 message 만 돌려준다. 저장된 것이 없어 같은 requestId 재시도는 모델을 다시 부르므로, 처리 중 표식은
     * 풀어 재시도가 POSTER-004 로 막히지 않게 한다.
     */
    private TemplateCreateResponse chatOnlyResponse(String requestId, GeneratedTemplate generated) {
        templateGenerationRateLimiter.releaseInFlight(requestId);
        return TemplateCreateResponse.chatOnly(generated.message());
    }

    private GeneratedTemplate generate(String requestPrompt) {
        try {
            return templateHtmlGenerator.generate(requestPrompt);
        } catch (TemplateGenerationTimeoutException e) {
            throw new CampaignException(PosterErrorCode.GENERATION_TIMEOUT, e);
        }
    }

    /*
     * template_quota 행 하나를 조건부 UPDATE 로 먼저 늘려서 자리를 확보한다.
     * 영향받은 행이 0이면 이미 한도라 거부한다. 이 UPDATE 가 행에 쓰기 잠금을 걸기 때문에,
     * 동시에 들어온 다른 요청은 이 트랜잭션이 끝날 때까지 기다렸다가 갱신된 값으로 조건을 다시 본다.
     *
     * 같은 requestId 로 동시에 들어온 두 요청은 위의 조회를 둘 다 빈 상태로 지나칠 수 있다.
     * 그 경쟁은 request_id 의 UNIQUE 제약이 막는다: saveAndFlush 로 실제 INSERT 를 이
     * 트랜잭션 안에서 바로 실행해 제약 위반을 이 시점에 드러낸다. 예외는 여기서 잡지
     * 않고 그대로 던진다. 트랜잭션 안에서 잡으면 Spring 이 이미 rollback-only 로 표시해
     * 둬서, transactionTemplate 이 커밋하려는 순간 UnexpectedRollbackException 이 터진다.
     * 대신 트랜잭션을 깨끗이 롤백시키고(쿼터 증가분도 함께 롤백됨), 호출한 쪽인 create()
     * 가 트랜잭션 밖에서 다시 조회해 먼저 저장된 버전으로 응답한다.
     */
    private TemplateCreateResponse save(
            String name, String requestPrompt, String requestId, Long adminId, TemplateSanitizeResult sanitized,
            String message, List<String> suggestedPrompts) {
        if (templateQuotaRepository.tryReserve(maxTemplates) == 0) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        Template template = Template.createDraft(name);
        TemplateVersion version = template.addDraftVersion(
                adminId, LocalDateTime.now(clock), requestPrompt, requestId, sanitized.html(), message, 1);
        templateRepository.saveAndFlush(template);
        return toResponse(version, sanitized.removedElements(), suggestedPrompts);
    }

    /*
     * 재시도로 되돌려주는 응답은 removedElements 를 비워 둔다. 그 목록은 저장하지 않아
     * 처음 응답 이후로는 다시 만들어낼 수 없다.
     */
    private TemplateCreateResponse toResponse(
            TemplateVersion version, List<String> removedElements, List<String> suggestedPrompts) {
        Template template = version.getTemplate();
        return new TemplateCreateResponse(
                template.getId(),
                template.getName(),
                version.getVersionNumber(),
                template.getStatus(),
                version.getHtmlContent(),
                version.getMessage() == null ? "" : version.getMessage(),
                removedElements,
                suggestedPrompts);
    }
}
