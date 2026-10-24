package com.launchcatch.admin.repository;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminStatus;
import com.launchcatch.admin.dto.AdminAccountResponse;
import com.launchcatch.auth.Role;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRepository extends JpaRepository<Admin, Long> {
    Optional<Admin> findByLoginId(String loginId);

    @Query("""
            select new com.launchcatch.admin.dto.AdminAccountResponse(a.loginId, a.name, a.role)
              from Admin a
             where a.status = :status and a.role in :roles
               and (lower(a.loginId) like :pattern escape '!' or lower(a.name) like :pattern escape '!')
             order by a.createdAt desc, a.id desc
            """)
    List<AdminAccountResponse> findAccounts(
            @Param("status") AdminStatus status,
            @Param("roles") List<Role> roles,
            @Param("pattern") String pattern);

    // 캐시가 없거나 장애가 났을 때, Refresh Token 해시로 관리자 ID를 조회함.
    @Query("select a.id from Admin a where a.refreshTokenHash = :hash")
    Optional<Long> findIdByRefreshTokenHash(@Param("hash") String hash);

    // 계정 발급과 로그인 백업 갱신이 끝날 때까지 현재 권한과 상태를 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admin a where a.id = :id")
    Optional<Admin> findByIdForUpdate(@Param("id") Long id);

}
