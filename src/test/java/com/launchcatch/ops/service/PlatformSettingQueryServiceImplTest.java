package com.launchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.ops.entity.PlatformSetting;
import com.launchcatch.ops.repository.PlatformSettingRepository;
import java.lang.reflect.Constructor;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PlatformSettingQueryServiceImplTest {

    @Mock
    private PlatformSettingRepository platformSettingRepository;

    private PlatformSettingQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PlatformSettingQueryServiceImpl(platformSettingRepository);
    }

    @Test
    @DisplayName("설정값을 정수로 파싱해 돌려준다")
    void 설정값을_정수로_돌려준다() {
        when(platformSettingRepository.findBySettingKey("IMPRESSION_UNIT_PRICE"))
                .thenReturn(Optional.of(settingOf("10")));

        assertThat(service.getImpressionUnitPrice()).isEqualTo(10);
    }

    @Test
    @DisplayName("키가 없으면 예외로 거부한다")
    void 키가_없으면_거부한다() {
        when(platformSettingRepository.findBySettingKey("IMPRESSION_UNIT_PRICE")).thenReturn(Optional.empty());

        assertThatThrownBy(service::getImpressionUnitPrice).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("값이 정수로 파싱되지 않으면 예외로 거부한다")
    void 파싱이_안되면_거부한다() {
        when(platformSettingRepository.findBySettingKey("IMPRESSION_UNIT_PRICE"))
                .thenReturn(Optional.of(settingOf("not-a-number")));

        assertThatThrownBy(service::getImpressionUnitPrice).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("값이 0이면 예외로 거부한다")
    void 값이_0이면_거부한다() {
        when(platformSettingRepository.findBySettingKey("IMPRESSION_UNIT_PRICE"))
                .thenReturn(Optional.of(settingOf("0")));

        assertThatThrownBy(service::getImpressionUnitPrice).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("값이 음수면 예외로 거부한다")
    void 값이_음수면_거부한다() {
        when(platformSettingRepository.findBySettingKey("IMPRESSION_UNIT_PRICE"))
                .thenReturn(Optional.of(settingOf("-5")));

        assertThatThrownBy(service::getImpressionUnitPrice).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("최소 하루 예산을 정수로 파싱해 돌려준다")
    void 최소_하루예산을_정수로_돌려준다() {
        when(platformSettingRepository.findBySettingKey("MIN_DAILY_BUDGET"))
                .thenReturn(Optional.of(settingOf("1000")));

        assertThat(service.getMinDailyBudget()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("최소 하루 예산 키가 없으면 예외로 거부한다")
    void 최소_하루예산_키가_없으면_거부한다() {
        when(platformSettingRepository.findBySettingKey("MIN_DAILY_BUDGET")).thenReturn(Optional.empty());

        assertThatThrownBy(service::getMinDailyBudget).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("최소 하루 예산이 0 이하면 예외로 거부한다")
    void 최소_하루예산이_0이하면_거부한다() {
        when(platformSettingRepository.findBySettingKey("MIN_DAILY_BUDGET"))
                .thenReturn(Optional.of(settingOf("0")));

        assertThatThrownBy(service::getMinDailyBudget).isInstanceOf(IllegalStateException.class);
    }

    private PlatformSetting settingOf(String value) {
        try {
            Constructor<PlatformSetting> constructor = PlatformSetting.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            PlatformSetting setting = constructor.newInstance();
            ReflectionTestUtils.setField(setting, "settingValue", value);
            return setting;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
