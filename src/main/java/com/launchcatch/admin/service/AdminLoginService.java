package com.launchcatch.admin.service;

import com.launchcatch.admin.dto.AdminLoginRequest;
import com.launchcatch.admin.dto.AdminLoginResult;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminLoginService {
    private static final int BCRYPT_MAX_BYTES = 72;
    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminSessionService sessions;
    private final AdminLoginAuditService loginAudit;
    private final String dummyPasswordHash;

    public AdminLoginService(AdminRepository adminRepository, PasswordEncoder passwordEncoder,
            AdminSessionService sessions, AdminLoginAuditService loginAudit) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.loginAudit = loginAudit;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-admin-login");
    }

    @Transactional(propagation = Propagation.NEVER)
    public AdminLoginResult login(AdminLoginRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<Admin> found = adminRepository.findByLoginId(request.loginId());
        try {
            AdminLoginResult result = authenticate(request, found);
            loginAudit.record(result.response().adminId(), true);
            return result;
        } catch (RuntimeException failure) {
            if (found.isPresent()) {
                int status = failure instanceof AuthException authFailure
                        ? authFailure.getErrorCode().getHttpStatus().value() : 500;
                loginAudit.record(found.get().getId(), false, status);
            } else {
                loginAudit.recordUnknownAccount();
            }
            throw failure;
        }
    }

    private AdminLoginResult authenticate(AdminLoginRequest request, Optional<Admin> found) {
        String hash = found.map(Admin::getPasswordHash).orElse(dummyPasswordHash);
        boolean tooLong = request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
        /*
         * 계정이 없어도 같은 인코더로 BCrypt 비교를 수행해 계정 존재 여부를 숨긴다.
         * 72바이트 초과 입력은 인코더에 전달하지 않되 더미 비교를 수행한 후 같은 실패로 처리한다.
         */
        boolean matches = passwordEncoder.matches(tooLong ? "invalid-overlong-password" : request.password(), hash);
        if (found.isEmpty() || tooLong || !matches || !found.get().isActive() || !found.get().getRole().isAdmin()) {
            throw new AuthException(AuthErrorCode.LOGIN_FAILED);
        }

        Admin admin = found.get();
        return sessions.issue(admin.getId(), hash);
    }
}
