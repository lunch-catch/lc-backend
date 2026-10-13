package com.launchcatch.ops.repository;

import com.launchcatch.ops.entity.PlatformSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, Long> {

    Optional<PlatformSetting> findBySettingKey(String settingKey);
}
