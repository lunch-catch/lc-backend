package com.lunchcatch.global.measure;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/*
 * 동시성 측정의 반복 횟수와 스텁 지연을 환경변수에서 읽는다(#1090).
 *
 * 기본값은 작게 둬서 ./gradlew check 의 실행 시간을 늘리지 않는다. 측정할 때만 환경변수로 키운다.
 * 값이 잘못되면 기본값으로 대신하지 않고 예외를 던진다. 측정이 다른 조건으로 조용히 돌면 결과 문서의 N 이 틀린다.
 */
public record MeasureSettings(int rounds, List<Long> delaysMillis) {

    public static final String ROUNDS_ENV = "MEASURE_ROUNDS";
    public static final String DELAYS_ENV = "MEASURE_DELAYS_MS";

    // 두 번째 동작의 시작 시점을 흔드는 난수열의 씨앗이다.
    public static final long SEED = 1090L;

    static final int DEFAULT_ROUNDS = 5;
    static final List<Long> DEFAULT_DELAYS_MILLIS = List.of(0L, 100L, 500L);

    // S3 읽기 5초와 Rekognition 5초 중 긴 쪽이다. 코드의 시간 제한을 넘는 지연은 실제로 일어나지 않는다.
    static final long MAX_DELAY_MILLIS = 5_000L;

    public MeasureSettings {
        if (rounds < 1) {
            throw new IllegalArgumentException(ROUNDS_ENV + " 는 1 이상이어야 한다: " + rounds);
        }
        if (delaysMillis.isEmpty()) {
            throw new IllegalArgumentException(DELAYS_ENV + " 에 값이 하나도 없다");
        }
        for (long delay : delaysMillis) {
            if (delay < 0 || delay > MAX_DELAY_MILLIS) {
                throw new IllegalArgumentException(
                        DELAYS_ENV + " 의 값은 0 이상 " + MAX_DELAY_MILLIS + " 이하여야 한다: " + delay);
            }
        }
        delaysMillis = List.copyOf(delaysMillis);
    }

    public static MeasureSettings fromEnvironment() {
        return from(System::getenv);
    }

    static MeasureSettings from(Function<String, String> environment) {
        return new MeasureSettings(
                parseRounds(environment.apply(ROUNDS_ENV)),
                parseDelays(environment.apply(DELAYS_ENV)));
    }

    private static int parseRounds(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ROUNDS;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ROUNDS_ENV + " 가 정수가 아니다: " + raw, e);
        }
    }

    private static List<Long> parseDelays(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DELAYS_MILLIS;
        }
        try {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(DELAYS_ENV + " 가 쉼표로 나눈 정수 목록이 아니다: " + raw, e);
        }
    }
}
