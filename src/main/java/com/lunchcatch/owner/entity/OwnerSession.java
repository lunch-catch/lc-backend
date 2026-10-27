package com.lunchcatch.owner.entity;

import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "owner_session")
@AttributeOverride(name = "id", column = @Column(name = "owner_session_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerSession extends BaseTimeEntity {
    @Column(name = "session_key", nullable = false, unique = true, length = 36)
    private String sessionKey;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(nullable = false)
    private long generation;
    @Column(name = "rotation_version", nullable = false)
    private long rotationVersion = 1L;
    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 64)
    private String refreshTokenHash;
    @Column(name = "previous_token_hash", length = 64)
    private String previousTokenHash;
    @Column(name = "previous_valid_until")
    private LocalDateTime previousValidUntil;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    private OwnerSession(Long ownerId, long generation, String hash, LocalDateTime expiresAt, LocalDateTime now) {
        if (ownerId == null || ownerId <= 0 || generation < 0) {
            throw new IllegalArgumentException("점주와 세션 세대가 올바르지 않습니다.");
        }
        validate(hash, expiresAt, now);
        this.sessionKey = UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.generation = generation;
        this.refreshTokenHash = hash;
        this.expiresAt = expiresAt;
    }

    public static OwnerSession create(Long ownerId, long generation, String hash, LocalDateTime expiresAt, LocalDateTime now) {
        return new OwnerSession(ownerId, generation, hash, expiresAt, now);
    }

    public boolean isActive(long currentGeneration, LocalDateTime now) {
        return revokedAt == null && generation == currentGeneration && expiresAt.isAfter(now);
    }

    public boolean isWithinGrace(LocalDateTime now) {
        return previousValidUntil != null && previousValidUntil.isAfter(now);
    }

    public void rotate(String hash, LocalDateTime expiresAt, LocalDateTime now, LocalDateTime graceUntil) {
        validate(hash, expiresAt, now);
        if (revokedAt != null || !this.expiresAt.isAfter(now) || graceUntil == null || !graceUntil.isAfter(now)) {
            throw new IllegalStateException("유효한 세션과 갱신 유예 시각이 필요합니다.");
        }
        rotationVersion = Math.incrementExact(rotationVersion);
        previousTokenHash = refreshTokenHash;
        previousValidUntil = graceUntil;
        refreshTokenHash = hash;
        this.expiresAt = expiresAt;
    }

    public void revoke(LocalDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("폐기 시각이 필요합니다.");
        }
        if (revokedAt == null) {
            rotationVersion = Math.incrementExact(rotationVersion);
            revokedAt = now;
        }
    }

    private static void validate(String hash, LocalDateTime expiresAt, LocalDateTime now) {
        if (hash == null || !hash.matches("[0-9a-f]{64}") || now == null || expiresAt == null || !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("토큰 해시와 유효한 만료 시각이 필요합니다.");
        }
    }
}
