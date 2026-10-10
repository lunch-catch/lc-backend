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

    @Query("select o.refreshTokenIssuanceVersion from Owner o where o.id = :id")
    Optional<Long> findIssuanceVersionById(@Param("id") Long id);

    boolean existsByEmail(String email);

    Optional<Owner> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Owner o where o.id = :id")
    Optional<Owner> findByIdForLogin(@Param("id") Long id);
}