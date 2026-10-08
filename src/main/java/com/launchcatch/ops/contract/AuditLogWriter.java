package com.launchcatch.ops.contract;

public interface AuditLogWriter {
    void write(Long adminId, String action, String target, String detail);
}