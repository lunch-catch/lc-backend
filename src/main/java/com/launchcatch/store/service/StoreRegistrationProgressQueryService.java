package com.launchcatch.store.service;

import com.launchcatch.store.dto.StoreRegistrationProgressResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주가 지금 자신의 가게 등록 진행 상태(재개 단계, 버전)를 조회한다(#375). 다음 단계
 * 완료 요청에 실어 보낼 버전을 얻는 자리라, 아직 없으면 initialize로 만들어 돌려준다.
 *
 * ONBOARDING이 아닌 점주를 거부하는 검사는 두지 않는다. 그 전환(ACTIVE/SUSPENDED/WITHDRAWN)이
 * 아직 이 저장소에 구현돼 있지 않고, 나중에 필요해지면 owner가 이벤트로 알리는 쪽으로 풀어야
 * owner<->store 순환 의존이 생기지 않는다(런치캐치_도메인_구조와_의존성_설계.md 2.3).
 */
@Service
@RequiredArgsConstructor
public class StoreRegistrationProgressQueryService {

    private final StoreRegistrationProgressService progressService;

    @Transactional
    public StoreRegistrationProgressResponse getProgress(Long ownerId) {
        var snapshot = progressService.initialize(ownerId);
        return new StoreRegistrationProgressResponse(snapshot.storeId(), snapshot.resumeStep(), snapshot.version());
    }
}
