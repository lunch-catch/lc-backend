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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
     * 포스터 생성은 템플릿을 한 행으로 읽으므로 재게시와 겹쳐도 이전 또는 새 HTML 중 하나로 일관되게 만들어진다.
     */
    @Transactional
    public TemplatePublishResponse publish(Long templateId, int versionNumber, Long adminId) {
        Template template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() == TemplateStatus.PUBLISHED) {
            return republish(template, versionNumber, adminId);
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
     * 게시된 템플릿의 재게시다(#880). 살아 있는 임시저장 버전을 고르면 그 HTML 을 게시본으로 교체하고 임시저장을
     * 모두 정리한다. active 는 바꾸지 않아 점주는 계속 쓴다.
     *
     * 고른 번호가 살아 있는 임시저장이 아니면 먼저 재시도인지 본다. 게시는 LLM 을 안 불러
     * (templateId, versionNumber) 만으로 결과가 결정되므로, 지금 게시된 버전 번호와 같으면 응답을 못 받은
     * 클라이언트의 재시도로 보고 지금 상태를 그대로 돌려준다. 임시저장이 하나도 없는데 다른 번호로 게시하려는
     * 요청은 진짜 충돌이라 거부하고(POSTER-007), 임시저장은 있는데 그 번호가 없으면 POSTER-008 이다.
     */
    private TemplatePublishResponse republish(Template template, int versionNumber, Long adminId) {
        Long templateId = template.getId();
        Optional<TemplateVersion> draft = templateVersionRepository
                .findByTemplateIdAndVersionNumberAndDeletedAtIsNull(templateId, versionNumber);
        if (draft.isPresent()) {
            LocalDateTime now = LocalDateTime.now(clock);
            template.republish(draft.get().getHtmlContent(), now, versionNumber, adminId);
            templateVersionRepository.softDeleteAllByTemplateId(templateId, now);
            log.info("event=TEMPLATE_REPUBLISHED templateId={} adminId={} versionNumber={} active={}",
                    templateId, adminId, versionNumber, template.isActive());
            return toResponse(template);
        }
        if (Integer.valueOf(versionNumber).equals(template.getPublishedVersionNumber())) {
            return toResponse(template);
        }
        if (!templateVersionRepository.existsByTemplateIdAndDeletedAtIsNull(templateId)) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_ALREADY_PUBLISHED);
        }
        throw new CampaignException(PosterErrorCode.VERSION_NOT_FOUND);
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
