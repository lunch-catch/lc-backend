package com.lunchcatch.ops.service;

import com.lunchcatch.ops.contract.PlatformSettingQueryService;
import com.lunchcatch.ops.entity.PlatformSetting;
import com.lunchcatch.ops.repository.PlatformSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformSettingQueryServiceImpl implements PlatformSettingQueryService {

    private static final String IMPRESSION_UNIT_PRICE_KEY = "IMPRESSION_UNIT_PRICE";
    private static final String MIN_DAILY_BUDGET_KEY = "MIN_DAILY_BUDGET";

    private final PlatformSettingRepository platformSettingRepository;

    @Override
    public int getImpressionUnitPrice() {
        PlatformSetting setting = platformSettingRepository.findBySettingKey(IMPRESSION_UNIT_PRICE_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_setting missing key: " + IMPRESSION_UNIT_PRICE_KEY));
        int unitPrice;
        try {
            unitPrice = Integer.parseInt(setting.getSettingValue());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "platform_setting value is not an integer for key " + IMPRESSION_UNIT_PRICE_KEY
                            + ": " + setting.getSettingValue(), e);
        }
        /*
         * 파싱이 됐다는 것과 값이 유효하다는 것은 다르다. 0이나 음수가 그대로 나가면
         * 노출 차감이 전부 0원으로 계산되어 모든 캠페인이 예산 부족(NO_POINTS)으로
         * 판정될 수 있다(#198).
         */
        if (unitPrice <= 0) {
            throw new IllegalStateException(
                    "platform_setting value must be positive for key " + IMPRESSION_UNIT_PRICE_KEY
                            + ": " + unitPrice);
        }
        return unitPrice;
    }

    @Override
    public long getMinDailyBudget() {
        PlatformSetting setting = platformSettingRepository.findBySettingKey(MIN_DAILY_BUDGET_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        "platform_setting missing key: " + MIN_DAILY_BUDGET_KEY));
        long minDailyBudget;
        try {
            minDailyBudget = Long.parseLong(setting.getSettingValue());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "platform_setting value is not an integer for key " + MIN_DAILY_BUDGET_KEY
                            + ": " + setting.getSettingValue(), e);
        }
        if (minDailyBudget <= 0) {
            throw new IllegalStateException(
                    "platform_setting value must be positive for key " + MIN_DAILY_BUDGET_KEY
                            + ": " + minDailyBudget);
        }
        return minDailyBudget;
    }
}
