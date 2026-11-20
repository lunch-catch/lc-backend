package com.lunchcatch.campaign.template;

import com.lunchcatch.global.measure.MeasureSettings;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/*
 * 느린 모델 호출 측정의 스텁 지연, 조건, 반복 횟수를 환경변수에서 읽는다(#1269).
 *
 * 기본값은 짧게 둬서 ./gradlew check 의 실행 시간을 늘리지 않는다. 18.5초로 잴 때만 환경변수로 키운다.
 * 값이 잘못되면 기본값으로 대신하지 않고 예외를 던진다. 측정이 다른 조건으로 조용히 돌면 결과 문서의 조건이 틀린다.
 *
 * 반복 횟수는 다른 측정과 같은 MEASURE_ROUNDS 를 쓴다.
 */
public record SlowCallMeasureSettings(long delayMillis, List<Mode> modes, int rounds) {

    public static final String DELAY_ENV = "MEASURE_SLOW_CALL_DELAY_MS";
    public static final String MODES_ENV = "MEASURE_SLOW_CALL_MODES";

    static final long DEFAULT_DELAY_MILLIS = 1_000L;
    static final List<Mode> DEFAULT_MODES = List.of(Mode.SINGLE, Mode.CORRECTION_RETRY);
    static final int DEFAULT_ROUNDS = 2;

    // 이보다 짧으면 8명의 요청이 겹치지 않고 차례로 끝나 동시 호출 상한을 재지 못한다.
    static final long MIN_DELAY_MILLIS = 500L;

    // BedrockConfig 가 호출 하나를 30초에서 끊는다. 그보다 긴 응답은 실제로 일어나지 않는다.
    static final long MAX_DELAY_MILLIS = 30_000L;

    public enum Mode {
        // 정상 HTML 을 한 번에 돌려준다. 요청 하나가 모델을 한 번 부른다.
        SINGLE,
        // 첫 응답은 슬롯 검증에 걸리고 교정 지시가 붙은 두 번째 응답이 통과한다. 요청 하나가 모델을 두 번 부른다.
        CORRECTION_RETRY
    }

    public SlowCallMeasureSettings {
        if (delayMillis < MIN_DELAY_MILLIS || delayMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException(
                    DELAY_ENV + " 는 " + MIN_DELAY_MILLIS + " 이상 " + MAX_DELAY_MILLIS + " 이하여야 한다: " + delayMillis);
        }
        if (modes.isEmpty()) {
            throw new IllegalArgumentException(MODES_ENV + " 에 값이 하나도 없다");
        }
        if (rounds < 1) {
            throw new IllegalArgumentException(MeasureSettings.ROUNDS_ENV + " 는 1 이상이어야 한다: " + rounds);
        }
        modes = List.copyOf(modes);
    }

    public static SlowCallMeasureSettings fromEnvironment() {
        return from(System::getenv);
    }

    static SlowCallMeasureSettings from(Function<String, String> environment) {
        return new SlowCallMeasureSettings(
                parseDelay(environment.apply(DELAY_ENV)),
                parseModes(environment.apply(MODES_ENV)),
                parseRounds(environment.apply(MeasureSettings.ROUNDS_ENV)));
    }

    private static long parseDelay(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DELAY_MILLIS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(DELAY_ENV + " 가 정수가 아니다: " + raw, e);
        }
    }

    private static List<Mode> parseModes(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODES;
        }
        try {
            // 한도를 -1 로 줘서 끝의 빈 항목(SINGLE,)을 버리지 않고 valueOf 로 거부한다.
            return Arrays.stream(raw.split(",", -1))
                    .map(String::trim)
                    .map(Mode::valueOf)
                    .distinct()
                    .toList();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(MODES_ENV + " 가 쉼표로 나눈 조건 이름(SINGLE, CORRECTION_RETRY)이 아니다: " + raw, e);
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
