package com.launchcatch.member.repository;

import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.entity.Member;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByProviderUserId(String providerUserId);

    Optional<Member> findByRefreshTokenHash(String refreshTokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Member member
               SET member.refreshTokenHash = :refreshTokenHash,
                   member.refreshTokenExpiresAt = :refreshTokenExpiresAt,
                   member.updatedAt = :updatedAt
             WHERE member.id = :memberId
            """)
    int updateRefreshTokenBackup(
            @Param("memberId") Long memberId,
            @Param("refreshTokenHash") String refreshTokenHash,
            @Param("refreshTokenExpiresAt") LocalDateTime refreshTokenExpiresAt,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Member member
               SET member.refreshTokenHash = NULL,
                   member.refreshTokenExpiresAt = NULL,
                   member.updatedAt = :updatedAt
             WHERE member.id = :memberId
               AND member.refreshTokenHash = :refreshTokenHash
            """)
    int clearRefreshTokenBackupIfHashMatches(
            @Param("memberId") Long memberId,
            @Param("refreshTokenHash") String refreshTokenHash,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE Member member
               SET member.refreshTokenHash = :newRefreshTokenHash,
                   member.refreshTokenExpiresAt = :newRefreshTokenExpiresAt,
                   member.updatedAt = :updatedAt
             WHERE member.id = :memberId
               AND member.refreshTokenHash = :oldRefreshTokenHash
               AND member.refreshTokenExpiresAt > :now
               AND member.status = :activeStatus
            """)
    int rotateRefreshTokenBackupIfMatches(
            @Param("memberId") Long memberId,
            @Param("oldRefreshTokenHash") String oldRefreshTokenHash,
            @Param("newRefreshTokenHash") String newRefreshTokenHash,
            @Param("newRefreshTokenExpiresAt") LocalDateTime newRefreshTokenExpiresAt,
            @Param("now") LocalDateTime now,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("activeStatus") MemberStatus activeStatus
    );
}
