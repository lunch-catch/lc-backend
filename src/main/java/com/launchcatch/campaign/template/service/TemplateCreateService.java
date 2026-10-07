package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.client.TemplateGenerationTimeoutException;
import com.launchcatch.campaign.template.client.TemplateHtmlGenerator;
import com.launchcatch.campaign.template.dto.TemplateCreateResponse;
import com.launchcatch.campaign.template.dto.TemplateSanitizeResult;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class TemplateCreateService {

    static final int MAX_TEMPLATES = 10;

    private final TemplateRepository templateRepository;
    private final TemplateHtmlGenerator templateHtmlGenerator;
    private final TemplateHtmlSanitizer templateHtmlSanitizer;
    private final TemplateSlotValidator templateSlotValidator;
    private final TemplatePaletteValidator templatePaletteValidator;
    private final TransactionTemplate transactionTemplate;

    /*
     * LLM 호출은 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB 연결을 잡지 않으려는 것이다.
     * 저장만 트랜잭션으로 묶는다.
     */
    public TemplateCreateResponse create(String name, String requestPrompt) {
        if (templateRepository.count() >= MAX_TEMPLATES) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        TemplateSanitizeResult sanitized = templateHtmlSanitizer.sanitize(generate(requestPrompt));
        templateSlotValidator.validate(sanitized.html());
        templatePaletteValidator.validate(sanitized.html());
        return transactionTemplate.execute(status -> save(name, requestPrompt, sanitized));
    }

    private String generate(String requestPrompt) {
        try {
            return templateHtmlGenerator.generate(requestPrompt);
        } catch (TemplateGenerationTimeoutException e) {
            throw new CampaignException(PosterErrorCode.GENERATION_TIMEOUT, e);
        }
    }

    private TemplateCreateResponse save(String name, String requestPrompt, TemplateSanitizeResult sanitized) {
        Template template = Template.createDraft(name);
        TemplateVersion version = template.addDraftVersion(requestPrompt, sanitized.html());
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
