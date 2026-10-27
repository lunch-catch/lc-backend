package com.lunchcatch.store.contract;

import java.time.LocalDateTime;

/*
 * storeId는 2단계에서 생성한 가게 ID이며 finalizedAt은 서버가 기록한 확정 시각이다.
 * version은 최종 등록으로 1 증가한 온보딩 버전이다.
 */
public record StoreCompleteResult(Long storeId, LocalDateTime finalizedAt, long version) { }
