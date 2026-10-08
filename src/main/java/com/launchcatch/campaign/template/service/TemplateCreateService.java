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
    private final TemplateHtmlGenerator templateHtmlGenerator;
    private final TemplateHtmlSanitizer templateHtmlSanitizer;
    private final TemplateSlotValidator templateSlotValidator;
    private final TemplatePaletteValidator templatePaletteValidator;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /*
     * LLM 호출은 트랜잭션 밖에서 한다. 응답을 기다리는 동안 DB 연결을 잡지 않으려는 것이다.
     * 저장만 트랜잭션으로 묶는다. 여기서의 개수 확인은 LLM 을 괜히 부르지 않으려는
     * 빠른 실패용이고, 진짜 안전장치는 save() 안에서 같은 트랜잭션으로 다시 확인하는 것이다.
     */
    public TemplateCreateResponse create(String name, String requestPrompt, Long adminId) {
        if (templateRepository.count() >= MAX_TEMPLATES) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }
        Document document = Jsoup.parseBodyFragment(generate(requestPrompt));
        TemplateSanitizeResult sanitized = templateHtmlSanitizer.sanitize(document);
        templateSlotValidator.validate(document);
        templatePaletteValidator.validate(document);
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
     * LLM 호출(최대 30초)이 끝나고 여기 들어오기까지 다른 요청이 끼어들어 먼저 저장됐을
     * 수 있어서, 저장 직전에 같은 트랜잭션 안에서 개수를 다시 확인한다. 위쪽 확인과의
     * 간격이 수 밀리초로 줄어든다.
     */
    private TemplateCreateResponse save(String name, String requestPrompt, Long adminId, TemplateSanitizeResult sanitized) {
        if (templateRepository.count() >= MAX_TEMPLATES) {
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
