package com.lunchcatch.global.measure;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/*
 * 규모별 소요 시간 측정의 캠페인 규모와 반복 횟수를 환경변수에서 읽는다(#1091).
 *
 * MEASURE_SCALE 이 없으면 측정 테스트가 돌지 않는다. 테스트 쪽에서 같은 이름의 환경변수로 건너뛰므로
 * 이 클래스는 값이 있을 때만 불린다. 값이 잘못되면 기본값으로 대신하지 않고 예외를 던진다.
 * 측정이 다른 조건으로 조용히 돌면 결과 문서의 규모가 틀린다.
 *
 * 반복 횟수는 동시성 측정과 같은 MEASURE_ROUNDS 를 쓴다. 예열 1번은 이 횟수에 들지 않고 따로 돈다.
 */
public record ScaleMeasureSettings(List<Integer> scales, int rounds) {

    public static final String SCALE_ENV = "MEASURE_SCALE";

    static final int DEFAULT_ROUNDS = 3;

    // 캠페인 데이터 생성기가 받는 최솟값이다. 이보다 작으면 생성기가 측정 도중에 예외를 던진다.
    static final int MIN_SCALE = 2;

    // 규모 하나가 만드는 행이 JVM 힙 2GB 와 컨테이너 디스크를 넘지 않게 막는 상한이다.
    static final int MAX_SCALE = 100_000;

    public ScaleMeasureSettings {
        if (scales.isEmpty()) {
            throw new IllegalArgumentException(SCALE_ENV + " 에 값이 하나도 없다");
        }
        for (int scale : scales) {
            if (scale < MIN_SCALE || scale > MAX_SCALE) {
                throw new IllegalArgumentException(
                        SCALE_ENV + " 의 값은 " + MIN_SCALE + " 이상 " + MAX_SCALE + " 이하여야 한다: " + scale);
            }
        }
        if (rounds < 1) {
            throw new IllegalArgumentException(MeasureSettings.ROUNDS_ENV + " 는 1 이상이어야 한다: " + rounds);
        }
        scales = List.copyOf(scales);
    }

    public static ScaleMeasureSettings fromEnvironment() {
        return from(System::getenv);
    }

    static ScaleMeasureSettings from(Function<String, String> environment) {
        return new ScaleMeasureSettings(
                parseScales(environment.apply(SCALE_ENV)),
                parseRounds(environment.apply(MeasureSettings.ROUNDS_ENV)));
    }

    private static List<Integer> parseScales(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(SCALE_ENV + " 가 없다. 예: 1000,10000");
        }
        try {
            // 한도를 -1 로 줘서 끝의 빈 항목(1000,)을 버리지 않고 parseInt 로 거부한다.
            return Arrays.stream(raw.split(",", -1))
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SCALE_ENV + " 가 쉼표로 나눈 정수 목록이 아니다: " + raw, e);
        }
    }

    private static int parseRounds(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ROUNDS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(MeasureSettings.ROUNDS_ENV + " 가 정수가 아니다: " + raw, e);
        }
    }
}
