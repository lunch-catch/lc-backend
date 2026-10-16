package com.launchcatch.analytics.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnalyticsErrorCodeTest {

    @Test
    @DisplayName("분석 오류 코드는 API 명세의 연속 번호와 상태를 사용한다")
    void 분석_오류_코드는_계약을_따른다() {
        assertThat(AnalyticsErrorCode.INVALID_PERIOD.getCode()).isEqualTo("ANALYTICS-001");
        assertThat(AnalyticsErrorCode.INVALID_PERIOD.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(AnalyticsErrorCode.UNAGGREGATABLE_DATE.getCode()).isEqualTo("ANALYTICS-002");
        assertThat(AnalyticsErrorCode.UNAGGREGATABLE_DATE.getHttpStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(AnalyticsErrorCode.AGGREGATION_ALREADY_RUNNING.getCode()).isEqualTo("ANALYTICS-003");
        assertThat(AnalyticsErrorCode.AGGREGATION_ALREADY_RUNNING.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(AnalyticsErrorCode.UNSUPPORTED_EVENT_TYPE.getCode()).isEqualTo("ANALYTICS-004");
        assertThat(AnalyticsErrorCode.UNSUPPORTED_EVENT_TYPE.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(AnalyticsErrorCode.INVALID_EVENT_REQUEST.getCode()).isEqualTo("ANALYTICS-005");
        assertThat(AnalyticsErrorCode.INVALID_EVENT_REQUEST.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
