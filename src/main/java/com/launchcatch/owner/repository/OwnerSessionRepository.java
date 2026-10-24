package com.launchcatch.owner.repository;

import com.launchcatch.owner.entity.OwnerSession;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface OwnerSessionRepository extends JpaRepository<OwnerSession, Long> {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    Optional<OwnerSession> findBySessionKeyAndOwnerId(String sessionKey, Long ownerId);

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
    @Query("select s from OwnerSession s where s.refreshTokenHash = :hash or s.previousTokenHash = :hash")
    Optional<OwnerSession> findByTokenHash(@Param("hash") String hash);

    @Modifying
    @Query("delete from OwnerSession s where s.ownerId = :ownerId and s.expiresAt <= :now")
    void deleteExpired(@Param("ownerId") Long ownerId, @Param("now") LocalDateTime now);

    // 호출자가 점주 행을 먼저 잠가 로그인·갱신·폐기를 직렬화한다.
    @Query("select s from OwnerSession s where s.sessionKey = :key and s.ownerId = :ownerId")
    Optional<OwnerSession> findCurrent(@Param("key") String key, @Param("ownerId") Long ownerId);
}
