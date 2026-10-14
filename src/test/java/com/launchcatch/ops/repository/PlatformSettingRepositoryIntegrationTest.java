package com.launchcatch.ops.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.ops.entity.PlatformSetting;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/*
 * V3__ops_impression_unit_price.sql 이 실제로 시드한 값을 읽는다. 단위 테스트는 저장소를
 * 목으로 두므로 마이그레이션이 실제로 그 행을 넣었는지는 보장하지 않는다.
 */
@SpringBootTest
class PlatformSettingRepositoryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PlatformSettingRepository platformSettingRepository;

    @Test
    void IMPRESSION_UNIT_PRICE가_개발_기본값_10으로_시드돼_있다() {
        Optional<PlatformSetting> setting = platformSettingRepository.findBySettingKey("IMPRESSION_UNIT_PRICE");

        assertThat(setting).isPresent();
        assertThat(setting.get().getSettingValue()).isEqualTo("10");
    }
}
