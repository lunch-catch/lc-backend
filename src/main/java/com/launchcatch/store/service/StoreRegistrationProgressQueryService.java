package com.launchcatch.store.service;

import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.repository.StoreRegistrationProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreRegistrationProgressQueryService {
    private final StoreRegistrationProgressRepository progresses;

    // 최초 조회는 DB에 쓰지 않으며 진행 상태 생성은 점주 행을 잠근 약관 저장 트랜잭션에서 처리한다.
    @Transactional(readOnly = true)
    public StoreRegistrationProgressResponse getProgress(Long ownerId) {
        return progresses.findByOwnerId(ownerId)
                .map(progress -> new StoreRegistrationProgressResponse(
                        progress.getStoreId(), progress.getResumeStep(), progress.getVersion()))
                .orElseGet(() -> new StoreRegistrationProgressResponse(null, StoreRegistrationStep.TERMS, 0L));
    }
}
