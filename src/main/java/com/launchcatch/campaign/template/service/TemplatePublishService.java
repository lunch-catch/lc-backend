package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.TemplatePublishResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.entity.TemplateVersion;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TemplatePublishService {

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final Clock clock;

    /*
     * findByIdForUpdate로 행 잠금을 건다. TemplateReviseService.save()도 같은
     * 잠금을 쓰기 때문에, 수정 중인 템플릿을 동시에 게시하거나 게시 중인 템플릿을
     * 동시에 수정하는 경쟁에서 한쪽이 끝날 때까지 다른 쪽이 기다리게 된다.
     */
    @Transactional
    public TemplatePublishResponse publish(Long templateId, int versionNumber) {
        Template template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() == TemplateStatus.PUBLISHED) {
            return publishedRetryResponse(template, versionNumber);
        }
        TemplateVersion version = templateVersionRepository
                .findByTemplateIdAndVersionNumberAndDeletedAtIsNull(templateId, versionNumber)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.VERSION_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);
        template.publish(version.getHtmlContent(), now, versionNumber);
        templateVersionRepository.softDeleteAllByTemplateId(templateId, now);

        return toResponse(template);
    }

    /*
     * 게시는 LLM 을 안 불러 (templateId, versionNumber) 만으로 결과가 결정된다.
     * 이미 그 버전으로 게시돼 있으면 응답을 못 받은 클라이언트의 재시도로 보고
     * 지금 상태를 그대로 돌려준다. 다른 버전 번호로 다시 게시하려는 요청은
     * 진짜 충돌이라 거부한다.
     */
    private TemplatePublishResponse publishedRetryResponse(Template template, int versionNumber) {
        if (!Integer.valueOf(versionNumber).equals(template.getPublishedVersionNumber())) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_ALREADY_PUBLISHED);
        }
        return toResponse(template);
    }

    private TemplatePublishResponse toResponse(Template template) {
        return new TemplatePublishResponse(
                template.getId(),
                template.getName(),
                template.getStatus(),
                template.getHtmlContent(),
                template.getPublishedAt());
    }
}
