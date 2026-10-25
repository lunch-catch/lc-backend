package com.launchcatch.admin.repository;

import com.launchcatch.admin.entity.AdminLoginSession;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminLoginSessionRepository extends JpaRepository<AdminLoginSession, Long> {
    Optional<AdminLoginSession> findByAdminIdAndIssuanceVersion(Long adminId, long version);
    Optional<AdminLoginSession> findByRefreshTokenHash(String hash);

    @Query("select s.adminId from AdminLoginSession s where s.id = :id")
    Optional<Long> findAdminId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AdminLoginSession s where s.id = :id")
    Optional<AdminLoginSession> findByIdForUpdate(@Param("id") Long id);
}
