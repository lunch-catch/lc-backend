package com.launchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.launchcatch.ops.entity.AuditLog;
import com.launchcatch.ops.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class AuditLogWriterImplTest {
    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AuditLogWriterImpl writer = new AuditLogWriterImpl(repository);

    @Test
    void 행위자와_발급_대상을_감사_로그에_저장한다() {
        writer.write(1L, "ADMIN_ACCOUNT_CREATE", "admin01", "role=ADMIN");
        var captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(ReflectionTestUtils.getField(captor.getValue(), "adminId")).isEqualTo(1L);
        assertThat(ReflectionTestUtils.getField(captor.getValue(), "action")).isEqualTo("ADMIN_ACCOUNT_CREATE");
        assertThat(ReflectionTestUtils.getField(captor.getValue(), "target")).isEqualTo("admin01");
        assertThat(ReflectionTestUtils.getField(captor.getValue(), "detail")).isEqualTo("role=ADMIN");
    }

    @Test
    void 저장_실패를_삼키지_않는다() {
        var cause = new DataIntegrityViolationException("audit write failed");
        when(repository.saveAndFlush(any())).thenThrow(cause);
        assertThatThrownBy(() -> writer.write(1L, "ADMIN_ACCOUNT_CREATE", "admin01", "role=ADMIN"))
                .isSameAs(cause);
    }
}