package com.launchcatch.admin.entity;

import com.launchcatch.auth.Role;
import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * admin 테이블 (admin/V1__admin_init.sql). 삭제는 하드 삭제가 아니라 비활성화다.
 * 비활성화 기능은 포함하지 않는다.
 * 그래서 DB 컬럼명은 'DELETED' 지만 이 클래스의 의미는 "비활성화" 다.
 *
 * BaseTimeEntity 의 PK 필드는 이름이 그냥 id 라 기본 매핑 컬럼도 'id' 다.
 * 이 테이블의 실제 PK 컬럼명은 admin_id 라서 @AttributeOverride 로 다시 이어준다.
 * 안 하면 Hibernate 스키마 검증이 "admin 테이블에 id 컬럼이 없다" 며 기동을 막는다.
 */
@Entity
@Table(name = "admin")
@AttributeOverride(name = "id", column = @Column(name = "admin_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Admin extends BaseTimeEntity {

    /*
     * 코드에서 허용하는 최대 길이와 DB에 저장할 수 있는 최대 길이를 통일한다.
     * 검증을 통과한 값이 DB 컬럼 길이를 초과해 저장에 실패하는 것을 방지한다.
     */
    private static final int LOGIN_ID_MAX_LENGTH = 50;
    private static final int PASSWORD_HASH_MAX_LENGTH = 255;
    private static final int NAME_MAX_LENGTH = 50;

    @Column(name = "login_id", nullable = false, length = LOGIN_ID_MAX_LENGTH)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = PASSWORD_HASH_MAX_LENGTH)
    private String passwordHash;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AdminStatus status;

    /*
     * 기기 한 대분만 저장한다.
     * 로그인마다 현재 Refresh Token의 해시와 만료 시각을 함께 갱신한다.
     */
    @Column(name = "refresh_token_hash", length = 64)
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    // 관리자 행 잠금 아래 증가시키며 Redis 게시 순서의 기준으로 사용한다.
    @Column(name = "refresh_token_issuance_version", nullable = false)
    private long refreshTokenIssuanceVersion;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /*
     * 관리자 계정 발급 서비스는 아이디 중복과 비밀번호 정책 같은 유스케이스 규칙을 먼저 검사한 뒤
     * 이 팩터리로 ACTIVE 관리자 엔티티를 만든다. 외부에서 status를 주입할 수 없게 해 신규 계정은
     * 항상 활성 상태로 시작한다.
     */
    public static Admin register(String loginId, String passwordHash, String name, Role role) {
        return new Admin(loginId, passwordHash, name, role);
    }

    private Admin(String loginId, String passwordHash, String name, Role role) {
        validateLoginId(loginId);
        validatePasswordHash(passwordHash);
        validateName(name);
        if (role == null) {
            throw new IllegalArgumentException("role 은 필수다");
        }
        if (!role.isAdmin()) {
            throw new IllegalArgumentException("role 은 ADMIN 또는 SUPER_ADMIN이어야 한다");
        }
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.status = AdminStatus.ACTIVE;   // 외부 입력을 받지 않는다 (EC R4)
    }

    public boolean isActive() {
        return status == AdminStatus.ACTIVE;
    }

    public void issueRefreshToken(String tokenHash, LocalDateTime expiresAt) {
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Refresh Token 해시는 SHA-256 형식이어야 한다");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Refresh Token 만료 시각은 필수다");
        }
        if (!isActive()) {
            throw new IllegalStateException("비활성 관리자는 토큰을 발급받을 수 없다");
        }
        this.refreshTokenIssuanceVersion = Math.incrementExact(refreshTokenIssuanceVersion);
        this.refreshTokenHash = tokenHash;
        this.refreshTokenExpiresAt = expiresAt;
    }

    /*
     * 토큰 재사용 감지 시, 폐기함.
     * Refresh Token 해시와 만료 시각을 지우고, 발급 순번을 증가시킴.
     */
    public void revokeRefreshToken() {
        this.refreshTokenIssuanceVersion = Math.incrementExact(refreshTokenIssuanceVersion);
        this.refreshTokenHash = null;
        this.refreshTokenExpiresAt = null;
    }

    private static void validateLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("loginId 는 필수다");
        }
        if (loginId.length() > LOGIN_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "loginId 는 " + LOGIN_ID_MAX_LENGTH + "자를 넘을 수 없다: " + loginId.length());
        }
    }

    private static void validatePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash 는 필수다");
        }
        if (passwordHash.length() > PASSWORD_HASH_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "passwordHash 는 " + PASSWORD_HASH_MAX_LENGTH + "자를 넘을 수 없다: " + passwordHash.length());
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 은 필수다");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "name 은 " + NAME_MAX_LENGTH + "자를 넘을 수 없다: " + name.length());
        }
    }

}
