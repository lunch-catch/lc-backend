package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.repository.TemplateQuotaRepository;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * findByIdForUpdate로 잠근다(#517). TemplateReviseService.save()와 TemplatePublishService.publish()도
 * 같은 잠금을 쓰기 때문에, 삭제 중인 템플릿을 동시에 수정하거나 게시하는 요청은 이 트랜잭션이
 * 끝날 때까지 기다리게 된다. 수정 요청이 LLM 호출(트랜잭션 밖)을 끝내고 저장 단계에서 이
 * 잠금을 기다렸다가 들어오면, 그때는 템플릿이 이미 지워져 findByIdForUpdate가 빈 값을
 * 돌려줘 POSTER-005로 끝난다.
 *
 * 버전을 먼저 지우고 템플릿을 지운다. 순서를 바꾸면 템플릿 행이 먼저 없어져 FK로 버전을
 * 못 지운다.
 */
@Service
@RequiredArgsConstructor
public class TemplateDeleteService {

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final TemplateQuotaRepository templateQuotaRepository;

    @Transactional
    public void delete(Long templateId) {
        Template template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() != TemplateStatus.DRAFT) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_DRAFT);
        }
        templateVersionRepository.deleteAllByTemplateId(templateId);
        templateRepository.delete(template);
        templateQuotaRepository.release();
    }
}
