package com.lunchcatch.admin.service;

import com.lunchcatch.admin.dto.AdminAccountResponse;
import com.lunchcatch.admin.entity.Admin;
import com.lunchcatch.admin.entity.AdminStatus;
import com.lunchcatch.admin.repository.AdminRepository;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.exception.AuthErrorCode;
import com.lunchcatch.auth.exception.AuthException;
import com.lunchcatch.auth.jwt.CutoffPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAccountQueryService {

    private final AdminRepository adminRepository;
    private final AdminAccessTokenVerifier tokenVerifier;

    @Transactional(readOnly = true, timeout = 5)
    public List<AdminAccountResponse> list(
            Long requesterId, Role requesterRole, LocalDateTime issuedAt, Long issuanceVersion, String keyword) {
        if (requesterRole != Role.SUPER_ADMIN) {
            throw new AuthException(AuthErrorCode.ROLE_NOT_ALLOWED);
        }
        if (!tokenVerifier.isValidAfter(requesterRole, requesterId, issuedAt, issuanceVersion, CutoffPolicy.REQUIRED)) {
            throw new AuthException(AuthErrorCode.LOGIN_REQUIRED);
        }
        Admin requester = adminRepository.findById(requesterId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.ROLE_NOT_ALLOWED));
        if (!requester.isActive() || requester.getRole() != Role.SUPER_ADMIN) {
            throw new AuthException(AuthErrorCode.ROLE_NOT_ALLOWED);
        }

        String normalized = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        // 이스케이프 문자부터 치환하여 LIKE의 와일드카드도 입력한 문자 그대로 검색한다.
        String pattern = "%" + normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return adminRepository.findAccounts(AdminStatus.ACTIVE, List.of(Role.ADMIN, Role.SUPER_ADMIN), pattern);
    }
}
