package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.entity.TemplateStatus;
import com.launchcatch.campaign.template.repository.TemplateQuotaRepository;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import com.launchcatch.ops.contract.AuditLogWriter;
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
 *
 * 템플릿 영구 삭제는 되돌릴 수 없는 관리자 쓰기 작업이라 누가 어느 템플릿을 지웠는지 감사 로그에 남긴다(#877).
 * 다른 관리자 쓰기 작업과 같이 ops 의 공개 계약(AuditLogWriter)을 쓴다. 기록은 삭제와 같은 트랜잭션에 참여해서
 * 삭제가 롤백되면 기록도 남지 않고, 기록이 실패하면 삭제도 되돌아간다. 이름은 삭제하면 다시 읽을 수 없어
 * 상세에 남긴다.
 */
@Service
@RequiredArgsConstructor
public class TemplateDeleteService {

    private static final String AUDIT_ACTION = "TEMPLATE_DELETE";

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final TemplateQuotaRepository templateQuotaRepository;
    private final AuditLogWriter auditLogWriter;

    @Transactional
    public void delete(Long adminId, Long templateId) {
        Template template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        if (template.getStatus() != TemplateStatus.DRAFT) {
            throw new CampaignException(PosterErrorCode.TEMPLATE_NOT_DRAFT);
        }
        templateVersionRepository.deleteAllByTemplateId(templateId);
        templateRepository.delete(template);
        templateQuotaRepository.release();
        auditLogWriter.write(adminId, AUDIT_ACTION, String.valueOf(templateId), "name=" + template.getName());
    }
}
