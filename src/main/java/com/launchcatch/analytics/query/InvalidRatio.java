package com.launchcatch.analytics.query;

import java.math.BigDecimal;
import java.math.RoundingMode;

/*
 * 무효 비율과 그 경고의 계산이다. 이 식이 한 곳에만 있어야 분모가 바뀌어도 여기만 고친다.
 *
 * 비율은 무효 ÷ 유효이고 요구사항 BR-054 를 따른다. 유효가 0이면 나눌 수 없어 null 이다. 경고는 무효가 최소 건수
 * 이상이고, 유효가 0이거나 비율이 20% 를 넘을 때다. 20% 비교는 실수 나눗셈의 오차를 피하려고 무효 x 5 > 유효로 한다.
 */
public final class InvalidRatio {

    private static final int SCALE = 6;

    private InvalidRatio() {
    }

    /*
     * 유효가 0이면 null 이다.
     * 소수점 여섯 자리로 반올림하고 1을 넘을 수 있다
     */
    public static Double of(long validCount, long invalidCount) {
        if (validCount == 0) {
            return null;
        }
        return BigDecimal.valueOf(invalidCount)
                .divide(BigDecimal.valueOf(validCount), SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public static boolean warning(long validCount, long invalidCount, long minimumInvalidCount) {
        return invalidCount >= minimumInvalidCount && (validCount == 0 || invalidCount * 5 > validCount);
    }
}
