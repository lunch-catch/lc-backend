package com.launchcatch.admin.service;

import com.launchcatch.admin.dto.AdminRegistrationRequest;
import com.launchcatch.admin.dto.AdminRegistrationResponse;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminStatus;
import com.launchcatch.admin.exception.AdminErrorCode;
import com.launchcatch.admin.exception.AdminException;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.CutoffPolicy;
import java.time.LocalDateTime;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.global.exception.ConstraintViolations;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class AdminRegistrationService {

    private static final String LOGIN_ID_CONSTRAINT = "uk_admin_login_id";
    private static final String AUDIT_ACTION = "ADMIN_ACCOUNT_CREATE";
    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final AdminRepository adminRepository;
    private final AuditLogWriter auditLogWriter;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;
    private final AdminAccessTokenVerifier cutoffVerifier;

    /*
     * BCrypt 해시는 DB 트랜잭션 밖에서 계산한다.
     * 계정 저장과 감사 로그 저장만 같은 DB 트랜잭션에 묶어 둘 중 하나라도 실패하면 함께 롤백한다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public AdminRegistrationResponse register(
            Long issuerAdminId,
            Role issuerRole,
            LocalDateTime issuedAt,
            Long issuanceVersion,
            AdminRegistrationRequest request) {
        Objects.requireNonNull(issuerAdminId, "issuerAdminId");
        Objects.requireNonNull(issuerRole, "issuerRole");
        Objects.requireNonNull(request, "request");

        if (issuerRole != Role.SUPER_ADMIN) {
            throw new AuthException(AuthErrorCode.ROLE_NOT_ALLOWED);
        }

        // 저장소 장애를 인증 실패와 구분하고, 해싱과 DB 작업 전에 발급을 중단한다.
        if (!cutoffVerifier.isValidAfter(issuerRole, issuerAdminId, issuedAt, issuanceVersion, CutoffPolicy.REQUIRED)) {
            throw new AuthException(AuthErrorCode.LOGIN_REQUIRED);
        }

        // 이미 존재하는 아이디에는 비용이 큰 BCrypt 계산을 하지 않는다.
        if (adminRepository.findByLoginId(request.loginId()).isPresent()) {
            throw new AdminException(AdminErrorCode.LOGIN_ID_DUPLICATED);
        }

        String passwordHash = passwordEncoder.encode(request.initialPassword());

        try {
            TransactionTemplate writeTransaction = new TransactionTemplate(transactionManager);
            writeTransaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
            return Objects.requireNonNull(
                    writeTransaction.execute(status -> saveAdminAndAudit(issuerAdminId, issuedAt, issuanceVersion, request, passwordHash)),
                    "registrationResult");
        } catch (DataIntegrityViolationException e) {
            // 사전 중복 검사 이후 동시에 같은 아이디가 생성되는 경쟁 상황은 DB UNIQUE 제약으로 최종 차단한다.
            if (ConstraintViolations.isConstraintViolation(e, LOGIN_ID_CONSTRAINT)) {
                throw new AdminException(AdminErrorCode.LOGIN_ID_DUPLICATED, e);
            }
            throw e;
        }
    }

    private AdminRegistrationResponse saveAdminAndAudit(
            Long issuerAdminId,
            LocalDateTime issuedAt,
            Long issuanceVersion,
            AdminRegistrationRequest request,
            String passwordHash) {
        // JWT 발급 이후 권한이나 상태가 바뀌었을 수 있어 현재 DB 값으로 다시 검사한다.
        Admin issuer = adminRepository.findByIdForUpdate(issuerAdminId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.ROLE_NOT_ALLOWED));
        if (issuer.getStatus() != AdminStatus.ACTIVE || issuer.getRole() != Role.SUPER_ADMIN) {
            throw new AuthException(AuthErrorCode.ROLE_NOT_ALLOWED);
        }

        // 로그아웃과 같은 행 잠금 아래 요청 토큰을 재검증해 해싱 중 완료된 폐기를 반영한다.
        boolean revoked = issuanceVersion != null
                ? issuanceVersion <= issuer.getAccessTokenRevokedVersion()
                : issuedAt == null || (issuer.getAccessTokenValidAfter() != null
                        && issuedAt.isBefore(issuer.getAccessTokenValidAfter()));
        if (revoked) {
            throw new AuthException(AuthErrorCode.LOGIN_REQUIRED);
        }
        Role role = Role.valueOf(request.role());
        Admin admin = Admin.register(request.loginId(), passwordHash, request.name(), role);
        Admin saved = adminRepository.saveAndFlush(admin);

        auditLogWriter.write(
                issuerAdminId,
                AUDIT_ACTION,
                saved.getLoginId(),
                "role=" + saved.getRole().name());

        return AdminRegistrationResponse.from(saved);
    }
}