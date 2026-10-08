package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminStatus;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLoginTransactionServiceTest {
    private final AdminRepository repository = mock(AdminRepository.class);
    private final RefreshTokenRepository cache = mock(RefreshTokenRepository.class);
    private final AdminLoginTransactionService service = new AdminLoginTransactionService(repository, cache);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0);
    private final String hash = "a".repeat(64);
    private Admin admin;

    @BeforeEach
    void setUp() {
        admin = Admin.register("admin01", "password-hash", "관리자", Role.SUPER_ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));
    }

    @Test
    void 잠금후_활성_계정에_해시와_만료시각을_함께_백업한다() {
        var result = service.issueRefreshToken(1L, "password-hash", hash, now.plusDays(1));
        assertThat(admin.getRefreshTokenHash()).isEqualTo(hash);
        assertThat(admin.getRefreshTokenExpiresAt()).isEqualTo(now.plusDays(1));
        assertThat(result.adminId()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("관리자");
        assertThat(result.role()).isEqualTo(Role.SUPER_ADMIN);
    }

    @Test
    void 계정이_사라졌으면_거부한다() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertRejected();
    }

    @Test
    void 잠금후_비활성_계정이면_거부한다() {
        ReflectionTestUtils.setField(admin, "status", AdminStatus.DELETED);
        assertRejected();
        assertThat(admin.getRefreshTokenHash()).isNull();
    }

    @Test
    void 비교후_비밀번호가_변경됐으면_거부한다() {
        ReflectionTestUtils.setField(admin, "passwordHash", "changed-hash");
        assertRejected();
    }

    @Test
    void 관리자_권한이_아니면_거부한다() {
        ReflectionTestUtils.setField(admin, "role", Role.MEMBER);
        assertRejected();
    }

    @Test
    void 보상은_이번_로그인에서_저장한_해시만_조건부_제거한다() {
        service.clearRefreshTokenIfMatches(1L, hash, now);
        verify(repository).clearRefreshTokenIfMatches(1L, hash, now);
    }

    @Test
    void 현재_DB_해시와_같은_토큰만_캐시에_게시한다() {
        String raw = "current-refresh-token";
        admin.issueRefreshToken(TokenHasher.sha256(raw), now.plusDays(1));
        assertThat(service.publishRefreshTokenIfCurrent(1L, Role.SUPER_ADMIN, raw, Duration.ofDays(1))).isTrue();
        verify(cache).save(raw, 1L, Role.SUPER_ADMIN, true, Duration.ofDays(1));
    }

    @Test
    void 이전_로그인_토큰은_최신_캐시를_덮어쓰지_않는다() {
        admin.issueRefreshToken(TokenHasher.sha256("new-token"), now.plusDays(1));
        assertThat(service.publishRefreshTokenIfCurrent(1L, Role.SUPER_ADMIN, "old-token", Duration.ofDays(1)))
                .isFalse();
        verifyNoInteractions(cache);
    }

    @Test
    void 캐시_게시_전에_계정이_사라졌으면_저장하지_않는다() {
        when(repository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertPublicationRejected(Role.SUPER_ADMIN);
        verifyNoInteractions(cache);
    }

    @Test
    void 캐시_게시_전에_계정이_비활성화됐으면_저장하지_않는다() {
        ReflectionTestUtils.setField(admin, "status", AdminStatus.DELETED);
        assertPublicationRejected(Role.SUPER_ADMIN);
        verifyNoInteractions(cache);
    }

    @Test
    void 캐시_게시_전에_역할이_바뀌었으면_이전_역할로_저장하지_않는다() {
        assertPublicationRejected(Role.ADMIN);
        verifyNoInteractions(cache);
    }

    private void assertPublicationRejected(Role role) {
        assertThatThrownBy(() -> service.publishRefreshTokenIfCurrent(1L, role, "raw", Duration.ofDays(1)))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_FAILED);
    }
    private void assertRejected() {
        assertThatThrownBy(() -> service.issueRefreshToken(1L, "password-hash", hash, now.plusDays(1)))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_FAILED);
    }
}
