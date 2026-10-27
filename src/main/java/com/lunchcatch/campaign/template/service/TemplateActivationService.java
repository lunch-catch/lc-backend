package com.lunchcatch.campaign.template.service;

import com.lunchcatch.admin.contract.AdminSummary;
import com.lunchcatch.admin.contract.AdminSummaryQuery;
import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.exception.PosterErrorCode;
import com.lunchcatch.campaign.template.dto.TemplateActiveUpdateResponse;
import com.lunchcatch.campaign.template.entity.Template;
import com.lunchcatch.campaign.template.entity.TemplateStatus;
import com.lunchcatch.campaign.template.repository.TemplateRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TemplateActivationService {

    private final TemplateRepository templateRepository;
    private final AdminSummaryQuery adminSummaryQuery;
    private final Clock clock;

    @Transactional
    public TemplateActiveUpdateResponse changeActive(Long templateId, boolean active, Long adminId) {
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() != TemplateStatus.PUBLISHED) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_PUBLISHED);
        }
        template.changeActive(active, adminId, LocalDateTime.now(clock));
        return toResponse(template, adminSummaryQuery.findSummariesByIds(Collections.singleton(adminId)).get(adminId));
    }

    // activatedBy 의 관리자를 찾을 수 없으면 admin 은 null 이고 이름과 로그인 ID 는 null 로 내려간다.
    private TemplateActiveUpdateResponse toResponse(Template template, AdminSummary admin) {
        return new TemplateActiveUpdateResponse(
                template.getId(),
                template.isActive(),
                template.getActivatedBy(),
                admin == null ? null : admin.name(),
                admin == null ? null : admin.loginId(),
                template.getActivatedAt());
    }
}
