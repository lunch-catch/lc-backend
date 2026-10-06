package com.launchcatch.admin.service;

import com.launchcatch.admin.dto.AdminRegistrationRequest;
import com.launchcatch.admin.dto.AdminRegistrationResponse;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminRole;
import com.launchcatch.admin.exception.AdminErrorCode;
import com.launchcatch.admin.exception.AdminException;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
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

    /*
     * BCrypt 해시는 DB 트랜잭션 밖에서 계산한다.
     * 계정 저장과 감사 로그 저장만 같은 DB 트랜잭션에 묶어 둘 중 하나라도 실패하면 함께 롤백한다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public AdminRegistrationResponse register(
            Long issuerAdminId,
            Role issuerRole,
            AdminRegistrationRequest request) {
        Objects.requireNonNull(issuerAdminId, "issuerAdminId");
        Objects.requireNonNull(issuerRole, "issuerRole");
        Objects.requireNonNull(request, "request");

        if (issuerRole != Role.SUPER_ADMIN) {
            throw new AuthException(AuthErrorCode.ROLE_NOT_ALLOWED);
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
                    writeTransaction.execute(status -> saveAdminAndAudit(issuerAdminId, request, passwordHash)),
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
            AdminRegistrationRequest request,
            String passwordHash) {
        AdminRole role = AdminRole.valueOf(request.role());
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