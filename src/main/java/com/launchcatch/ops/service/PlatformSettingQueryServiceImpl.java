package com.launchcatch.ops.service;

import com.launchcatch.ops.contract.PlatformSettingQueryService;
import com.launchcatch.ops.entity.PlatformSetting;
import com.launchcatch.ops.repository.PlatformSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformSettingQueryServiceImpl implements PlatformSettingQueryService {

    private static final String IMPRESSION_UNIT_PRICE_KEY = "IMPRESSION_UNIT_PRICE";

    private final PlatformSettingRepository platformSettingRepository;

    @Override
    public int getImpressionUnitPrice() {
        PlatformSetting setting = platformSettingRepository.findBySettingKey(IMPRESSION_UNIT_PRICE_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_setting missing key: " + IMPRESSION_UNIT_PRICE_KEY));
        try {
            return Integer.parseInt(setting.getSettingValue());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "platform_setting value is not an integer for key " + IMPRESSION_UNIT_PRICE_KEY
                            + ": " + setting.getSettingValue(), e);
        }
    }
}
