package com.lunchcatch.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lunchcatch.ops.contract.AuditLogWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;

class AdminLoginAuditServiceTest {
    private final AdminLoginAuditTransactionService transactions = mock(AdminLoginAuditTransactionService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AdminLoginAuditService service = new AdminLoginAuditService(transactions, meters);

    @AfterEach
    void tearDown() {
        MDC.clear();
        meters.close();
    }

    @Test
    void 실패_감사_저장_장애도_전파하지_않는다() {
        doThrow(new DataAccessResourceFailureException("audit down"))
                .when(transactions).record(1L, false, null);
        service.record(1L, false);
        assertThat(meters.get("admin.login.failed").tag("adminId", "1").counter().count()).isEqualTo(1);
        assertThat(meters.get("admin.login.audit.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void 없는_계정은_메트릭으로만_센다() {
        service.recordUnknownAccount();
        org.mockito.Mockito.verifyNoInteractions(transactions);
        assertThat(meters.get("admin.login.rejected").tag("reason", "UNKNOWN_ACCOUNT").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 성공_감사에_공유_IP를_전달한다() {
        MDC.put("clientIp", "203.0.113.20");
        service.record(1L, true);
        verify(transactions).record(1L, true, "203.0.113.20");
        assertThat(meters.find("admin.login.failed").counter()).isNull();
    }

    @Test
    void 실패_감사를_저장하고_계정별로_센다() {
        service.record(1L, false);
        verify(transactions).record(1L, false, null);
        assertThat(meters.get("admin.login.failed").tag("adminId", "1").counter().count()).isEqualTo(1);
    }

    @Test
    void 감사_저장_장애가_호출자에게_전파되지_않는다() {
        doThrow(new DataAccessResourceFailureException("SensitivePassword!"))
                .when(transactions).record(1L, true, null);
        service.record(1L, true);
        assertThat(meters.get("admin.login.audit.failed").counter().count()).isEqualTo(1);
    }

    @Test
    void 감사_저장_계약에_결과와_IP만_전달한다() {
        AuditLogWriter writer = mock(AuditLogWriter.class);
        new AdminLoginAuditTransactionService(writer).record(1L, false, "203.0.113.20");
        verify(writer).write(1L, "ADMIN_LOGIN", "1", "result=FAILURE;clientIp=203.0.113.20");
    }
}
