package com.launchcatch.campaign.template.service;

import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 게시로 소프트삭제된 템플릿 버전을 완전히 지운다(#412). TemplatePublishService.publish() 가
 * 게시 시점에 이미 소프트삭제를 하므로, 이 서비스는 그 뒤처리인 하드 삭제만 맡는다.
 *
 * 이 메서드 자체는 트랜잭션을 걸지 않는다. 리포지토리의 각 청크 호출이 Spring Data가
 * 주는 자기 트랜잭션으로 따로 커밋돼, 쌓인 양이 많아도 한 트랜잭션이 잠금을 오래 들고
 * 있지 않는다. 묶어서 하나로 커밋하려 하면 청크로 나눈 의미가 없어진다.
 */
@Service
@RequiredArgsConstructor
public class TemplateVersionPurgeService {

    static final int BATCH_SIZE = 500;

    private final TemplateVersionRepository templateVersionRepository;

    public int purgeSoftDeleted() {
        int total = 0;
        int deleted;
        do {
            deleted = templateVersionRepository.deleteBatchByDeletedAtIsNotNull(BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        return total;
    }
}
