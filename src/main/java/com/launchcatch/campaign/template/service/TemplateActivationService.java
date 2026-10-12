package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.TemplateActiveUpdateResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TemplateActivationService {

    private final TemplateRepository templateRepository;
    private final Clock clock;

    @Transactional
    public TemplateActiveUpdateResponse changeActive(Long templateId, boolean active, Long adminId) {
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() != TemplateStatus.PUBLISHED) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_PUBLISHED);
        }
        template.changeActive(active, adminId, LocalDateTime.now(clock));
        return toResponse(template);
    }

    private TemplateActiveUpdateResponse toResponse(Template template) {
        return new TemplateActiveUpdateResponse(
                template.getId(),
                template.isActive(),
                template.getActivatedBy(),
                template.getActivatedAt());
    }
}
