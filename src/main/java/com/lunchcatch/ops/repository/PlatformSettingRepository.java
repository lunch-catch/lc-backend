package com.lunchcatch.ops.repository;

import com.lunchcatch.ops.entity.PlatformSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, Long> {

    Optional<PlatformSetting> findBySettingKey(String settingKey);
}
