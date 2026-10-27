package com.lunchcatch.ops.repository;

import com.lunchcatch.ops.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}