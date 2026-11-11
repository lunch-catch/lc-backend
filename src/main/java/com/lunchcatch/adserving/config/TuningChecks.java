package com.lunchcatch.adserving.config;

import java.time.Duration;
import java.util.Objects;

// 조정 값 레코드가 함께 쓰는 검증이다. 어기면 앱이 뜨지 않는다
final class TuningChecks {

    private TuningChecks() {
    }

    static void positive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 은 0 보다 커야 한다");
        }
    }

    static void positive(String name, Duration value) {
        Objects.requireNonNull(value, name + " 이 필요하다");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 은 0 보다 길어야 한다");
        }
    }
}
