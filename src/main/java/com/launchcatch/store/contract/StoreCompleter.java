package com.launchcatch.store.contract;

/*
 * 점주 본인의 기존 초안을 최종 등록하며 메뉴는 0개여도 된다.
 * 호출자는 같은 쓰기 트랜잭션에서 점주 행을 먼저 잠그고 ONBOARDING 상태를 확인해야 한다.
 */
public interface StoreCompleter {
    /*
     * expectedVersion은 최신 조회에서 받은 온보딩 버전이다.
     * 버전 불일치나 필수 정보 누락 시 예외를 던진다.
     * 확정 이벤트도 같은 트랜잭션에서 처리하므로 수신 실패 시 호출 전체를 롤백한다.
     */
    StoreCompleteResult complete(Long ownerId, long expectedVersion);
}
