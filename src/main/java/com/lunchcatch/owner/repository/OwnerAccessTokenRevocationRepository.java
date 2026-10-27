package com.lunchcatch.owner.repository;

import com.lunchcatch.owner.entity.Owner;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface OwnerAccessTokenRevocationRepository extends Repository<Owner, Long> {
    // 이전 트랜잭션의 상태 대신 DB에서 확정된 폐기 순번을 조회한다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    @Query("select o.accessTokenRevokedVersion from Owner o where o.id = :id")
    Optional<Long> findRevokedVersion(@Param("id") Long id);
}
