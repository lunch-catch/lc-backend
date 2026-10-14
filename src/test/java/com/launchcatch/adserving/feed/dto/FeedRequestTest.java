package com.launchcatch.adserving.feed.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// 400 COMMON-002 로 가는 요청 형식 오류를 요청 타입의 애너테이션이 판정한다
class FeedRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 위치를_둘_다_보내거나_둘_다_보내지_않으면_통과한다() {
        assertThat(validator.validate(new FeedRequest(37.4979, 127.0276))).isEmpty();
        assertThat(validator.validate(new FeedRequest(null, null))).isEmpty();
    }

    @Test
    void 위도와_경도_중_하나만_보내면_거부한다() {
        assertThat(validator.validate(new FeedRequest(37.4979, null))).isNotEmpty();
        assertThat(validator.validate(new FeedRequest(null, 127.0276))).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"90.0001, 127", "-90.0001, 127", "37, 180.0001", "37, -180.0001"})
    void 범위_밖_좌표는_거부한다(double latitude, double longitude) {
        assertThat(validator.validate(new FeedRequest(latitude, longitude))).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"90, 180", "-90, -180"})
    void 범위_끝값은_받는다(double latitude, double longitude) {
        assertThat(validator.validate(new FeedRequest(latitude, longitude))).isEmpty();
    }
}
