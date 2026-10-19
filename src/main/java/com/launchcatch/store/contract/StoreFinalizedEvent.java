package com.launchcatch.store.contract;

/*
 * store가 가게 확정 후 발행하며 owner는 같은 트랜잭션에서 해당 점주를 ACTIVE로 전환한다.
 * DB 커밋 전 동기로 처리하므로 수신 실패 시 가게 확정도 롤백된다.
 */
public record StoreFinalizedEvent(Long ownerId, Long storeId) { }
