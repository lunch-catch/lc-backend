package com.launchcatch.admin.repository;

import com.launchcatch.admin.entity.Admin;
import java.util.Optional;
import java.time.LocalDateTime;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Optional<Admin> findByLoginId(String loginId);

    // 캐시가 없거나 장애가 났을 때, Refresh Token 해시로 관리자 ID를 조회함.
    @Query("select a.id from Admin a where a.refreshTokenHash = :hash")
    Optional<Long> findIdByRefreshTokenHash(@Param("hash") String hash);

    // 계정 발급과 로그인 백업 갱신이 끝날 때까지 현재 권한과 상태를 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admin a where a.id = :id")
    Optional<Admin> findByIdForUpdate(@Param("id") Long id);

    // 로그인 실패를 보상할 때 다른 로그인에서 저장한 토큰을 지우지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Admin a
               set a.refreshTokenHash = null, a.refreshTokenExpiresAt = null, a.updatedAt = :now
             where a.id = :id and a.refreshTokenHash = :tokenHash
            """)
    int clearRefreshTokenIfMatches(
            @Param("id") Long id, @Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);
}
