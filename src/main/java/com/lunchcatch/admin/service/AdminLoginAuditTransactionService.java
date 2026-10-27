package com.lunchcatch.admin.service;

import com.lunchcatch.ops.contract.AuditLogWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class AdminLoginAuditTransactionService {
    private final AuditLogWriter audit;

    // 인증 결과와 독립된 트랜잭션에서 감사를 확정한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    void record(Long adminId, boolean success, String clientIp) {
        audit.write(adminId, "ADMIN_LOGIN", String.valueOf(adminId),
                "result=" + (success ? "SUCCESS" : "FAILURE") + ";clientIp=" + (clientIp == null ? "unknown" : clientIp));
    }
}
