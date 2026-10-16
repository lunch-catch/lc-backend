package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 게시로 소프트삭제된 템플릿 버전을 완전히 지운다(#412). TemplatePublishService.publish() 가
 * 게시 시점에 이미 소프트삭제를 하므로, 이 서비스는 그 뒤처리인 하드 삭제만 맡는다.
 */
@Service
@RequiredArgsConstructor
public class TemplateVersionPurgeService {

    private final TemplateVersionRepository templateVersionRepository;

    @Transactional
    public int purgeSoftDeleted() {
        return templateVersionRepository.deleteAllByDeletedAtIsNotNull();
    }
}
