package com.launchcatch.adserving.impression.repository;

import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImpressionLogRepository extends JpaRepository<ImpressionLog, ImpressionLogId>, ImpressionLogInsert {
}
