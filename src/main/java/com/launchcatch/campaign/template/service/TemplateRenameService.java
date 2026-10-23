package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.template.dto.TemplateRenameResponse;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 템플릿 이름을 바꾼다(#880). 상태와 무관하게 바꿀 수 있고 lastModifiedBy 와 lastModifiedAt 을 갱신한다.
 * 이미 만든 포스터의 제목은 만들 때 정해져 저장돼 있어 바뀌지 않고, 이후 새로 만드는 포스터의 제목에만 반영된다.
 *
 * 수정, 게시, 삭제와 같은 행 잠금(findByIdForUpdate)을 쓴다. 이름 변경이 게시나 수정과 겹쳐도
 * lastModified 갱신이 서로 덮어쓰이지 않고 순서가 정해진다.
 */
@Service
@RequiredArgsConstructor
public class TemplateRenameService {

    private final TemplateRepository templateRepository;
    private final Clock clock;

    @Transactional
    public TemplateRenameResponse rename(Long templateId, String name, Long adminId) {
        Template template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.TEMPLATE_NOT_FOUND));
        template.rename(name, adminId, LocalDateTime.now(clock));
        return TemplateRenameResponse.of(template);
    }
}
