package com.launchcatch.admin.service;

import com.launchcatch.admin.AdminLoginAuditContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class AdminLoginAuditService {
    private final AdminLoginAuditTransactionService transactions;
    private final MeterRegistry meters;

    void recordUnknownAccount() {
        meters.counter("admin.login.rejected", "reason", "UNKNOWN_ACCOUNT").increment();
    }

    void record(Long adminId, boolean success) {
        record(adminId, success, success ? 200 : 401);
    }

    void record(Long adminId, boolean success, int httpStatus) {
        AdminLoginAuditContext.markHandled();
        if (success) {
            log.info("event=ADMIN_LOGIN_SUCCESS adminId={} httpStatus={}", adminId, httpStatus);
        } else if (httpStatus >= 500) {
            log.error("event=ADMIN_LOGIN_FAILED adminId={} httpStatus={}", adminId, httpStatus);
        } else {
            log.info("event=ADMIN_LOGIN_FAILED adminId={} httpStatus={}", adminId, httpStatus);
        }
        if (!success) {
            meters.counter("admin.login.failed", "adminId", String.valueOf(adminId)).increment();
        }
        try {
            transactions.record(adminId, success, MDC.get("clientIp"));
        } catch (RuntimeException failure) {
            // 감사 저장 장애가 인증 결과를 바꾸지 않으며 예외의 민감한 메시지는 남기지 않는다.
            log.error("event=ADMIN_LOGIN_AUDIT_FAILED adminId={} exceptionType={}",
                    adminId, failure.getClass().getSimpleName());
            meters.counter("admin.login.audit.failed").increment();
        }
    }
}
