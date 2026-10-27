package com.lunchcatch.ops.service;

import com.lunchcatch.ops.contract.AuditLogWriter;
import com.lunchcatch.ops.entity.AuditLog;
import com.lunchcatch.ops.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogWriterImpl implements AuditLogWriter {
    private final AuditLogRepository auditLogRepository;

    // 별도 트랜잭션을 만들지 않고 호출자의 계정 저장 트랜잭션에 반드시 참여한다.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(Long adminId, String action, String target, String detail) {
        auditLogRepository.saveAndFlush(AuditLog.of(adminId, action, target, detail));
    }
}