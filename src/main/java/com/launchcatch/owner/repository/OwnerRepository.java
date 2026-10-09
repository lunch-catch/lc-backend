package com.launchcatch.owner.repository;

import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OwnerRepository extends JpaRepository<Owner, Long> {
    // 점주 ID로 상태 컬럼만 조회하는 메서드.
    @Query("select o.status from Owner o where o.id = :id")
    Optional<OwnerStatus> findStatusById(@Param("id") Long id);

    boolean existsByEmail(String email);

    Optional<Owner> findByEmail(String email);

    /*
     * Refresh Token의 해시로 해당 토큰을 가진 점주를 DB에서 찾는 메서드.
     * Redis 장애 시 DB 기반 재발급을 위해 사용하며, 상태·만료 검증은 서비스에서 수행한다.
     * 평소에는 Redis를 통해 해당 Refresh Token을 가진 점주를 찾는다.
     */
    Optional<Owner> findByRefreshTokenHash(String refreshTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Owner o where o.id = :id")
    Optional<Owner> findByIdForLogin(@Param("id") Long id);
}