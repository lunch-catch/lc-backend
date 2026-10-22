package com.launchcatch.owner.contract;

import java.util.Optional;

/*
 * 다른 도메인은 이 인터페이스를 주입받아 점주 상태를 조회한다.
 * 점주가 없으면 Optional.empty()를 반환하고, DB 조회 장애는 호출자에게 전파한다.
 * 반환된 상태의 접근 허용 여부는 각 기능의 정책에서 판단한다.
 */
public interface OwnerStatusQuery {
    /*
     * 일반 조회는 Redis 캐시를 사용하며, 캐시 미스나 장애 시 DB에서 조회한다.
     * 트랜잭션 안에서는 DB를 조회하며 결과를 캐시에 저장하지 않는다.
     * 캐시 값은 TTL 동안 이전 상태일 수 있으므로 즉시 제한이 필요한 작업에는 사용하지 않는다.
     */
    Optional<OwnerAccountStatus> findStatus(Long ownerId);

    /*
     * 캐시를 우회해 DB 상태를 조회한다.
     * DB 트랜잭션의 격리 수준을 따르며, 조회 이후의 동시 상태 변경을 막는 잠금은 제공하지 않는다.
     */
    Optional<OwnerAccountStatus> findCurrentStatus(Long ownerId);
}
