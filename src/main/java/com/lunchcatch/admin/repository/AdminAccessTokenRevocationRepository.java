package com.lunchcatch.admin.repository;

import com.lunchcatch.admin.AdminAccessTokenRevocation;
import com.lunchcatch.admin.entity.Admin;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface AdminAccessTokenRevocationRepository extends Repository<Admin, Long> {
    // 이전 트랜잭션의 조회 결과를 재사용하지 않고 DB에서 확정된 폐기 기준을 읽는다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    @Query("select new com.lunchcatch.admin.AdminAccessTokenRevocation(a.accessTokenRevokedVersion, a.accessTokenValidAfter) from Admin a where a.id = :id")
    Optional<AdminAccessTokenRevocation> findRevocation(@Param("id") Long id);
}