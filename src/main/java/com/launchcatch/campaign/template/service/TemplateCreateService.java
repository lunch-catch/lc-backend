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
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class TemplateCreateService {

    static final int MAX_TEMPLATES = 10;

    private final TemplateRepository templateRepository;
    private final TemplateQuotaRepository templateQuotaRepository;
    private final TemplateHtmlGenerator templateHtmlGenerator;
    private final TemplateHtmlSanitizer templateHtmlSanitizer;
    private final TemplateSlotValidator templateSlotValidator;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /*
     * LLM 호출은 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB 연결을 잡지 않으려는 것이다.
     * 저장만 트랜잭션으로 묶는다. 여기서의 개수 확인은 LLM을 부르지 않으려는
     * 빠른 실패용일 뿐이고, 진짜 안전장치는 save() 안의 조건부 UPDATE 다.
     */
    public TemplateCreateResponse create(String name, String requestPrompt, Long adminId) {
        if (templateRepository.count() >= MAX_TEMPLATES) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        Document document = Jsoup.parseBodyFragment(generate(requestPrompt));
        TemplateSanitizeResult sanitized = templateHtmlSanitizer.sanitize(document);
        templateSlotValidator.validate(document);
        return transactionTemplate.execute(status -> save(name, requestPrompt, adminId, sanitized));
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
     */
    private TemplateCreateResponse save(String name, String requestPrompt, Long adminId, TemplateSanitizeResult sanitized) {
        if (templateQuotaRepository.tryReserve(MAX_TEMPLATES) == 0) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        Template template = Template.createDraft(name);
        TemplateVersion version =
                template.addDraftVersion(adminId, LocalDateTime.now(clock), requestPrompt, sanitized.html());
        Template saved = templateRepository.save(template);
        return new TemplateCreateResponse(
                saved.getId(),
                saved.getName(),
                version.getVersionNumber(),
                saved.getStatus(),
                version.getHtmlContent(),
                sanitized.removedElements());
    }
}
