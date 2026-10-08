package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.client.TemplateGenerationTimeoutException;
import com.launchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateQuotaRepository;
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
public class TemplateCreateService {

    static final int MAX_TEMPLATES = 10;

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final TemplateQuotaRepository templateQuotaRepository;
    private final TemplateHtmlGenerator templateHtmlGenerator;
    private final TemplateHtmlSanitizer templateHtmlSanitizer;
    private final TemplateSlotValidator templateSlotValidator;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /*
     * requestId 로 먼저 조회해 재시도인지 본다. 재시도면 LLM 을 다시 부르지 않고 그때 만든
     * 버전으로 바로 응답한다. LLM 호출은 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB
     * 연결을 잡지 않으려는 것이다. 저장만 트랜잭션으로 묶는다. 여기서의 개수 확인은
     * LLM을 부르지 않으려는 빠른 실패용일 뿐이고, 진짜 안전장치는 save() 안의 조건부
     * UPDATE 와 request_id 의 UNIQUE 제약이다.
     */
    public TemplateCreateResponse create(String name, String requestPrompt, String requestId, Long adminId) {
        var existing = templateVersionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return toResponse(existing.get(), List.of());
        }
        if (templateRepository.count() >= MAX_TEMPLATES) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        Document document = Jsoup.parseBodyFragment(generate(requestPrompt));
        TemplateSanitizeResult sanitized = templateHtmlSanitizer.sanitize(document);
        templateSlotValidator.validate(document);
        try {
            return transactionTemplate.execute(status -> save(name, requestPrompt, requestId, adminId, sanitized));
        } catch (DataIntegrityViolationException e) {
            return templateVersionRepository.findByRequestId(requestId)
                    .map(found -> toResponse(found, List.<String>of()))
                    .orElseThrow(() -> e);
        }
    }

    private String generate(String requestPrompt) {
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
            String name, String requestPrompt, String requestId, Long adminId, TemplateSanitizeResult sanitized) {
        if (templateQuotaRepository.tryReserve(MAX_TEMPLATES) == 0) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        Template template = Template.createDraft(name);
        TemplateVersion version = template.addDraftVersion(
                adminId, LocalDateTime.now(clock), requestPrompt, requestId, sanitized.html());
        templateRepository.saveAndFlush(template);
        return toResponse(version, sanitized.removedElements());
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
