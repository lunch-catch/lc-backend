package com.launchcatch.campaign.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PosterErrorCodeTest {

    @Test
    @DisplayName("포스터 오류 코드는 명세의 번호와 상태를 사용한다")
    void 포스터_오류_코드는_명세를_따른다() {
        assertThat(PosterErrorCode.SLOT_CONTRACT_VIOLATION.getCode()).isEqualTo("POSTER-001");
        assertThat(PosterErrorCode.SLOT_CONTRACT_VIOLATION.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(PosterErrorCode.STRUCTURE_CHANGED.getCode()).isEqualTo("POSTER-002");
        assertThat(PosterErrorCode.STRUCTURE_CHANGED.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED.getCode()).isEqualTo("POSTER-003");
        assertThat(PosterErrorCode.TEMPLATE_LIMIT_EXCEEDED.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(PosterErrorCode.GENERATION_TIMEOUT.getCode()).isEqualTo("POSTER-004");
        assertThat(PosterErrorCode.GENERATION_TIMEOUT.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(PosterErrorCode.TEMPLATE_NOT_FOUND.getCode()).isEqualTo("POSTER-005");
        assertThat(PosterErrorCode.TEMPLATE_NOT_FOUND.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(PosterErrorCode.TEMPLATE_NOT_DRAFT.getCode()).isEqualTo("POSTER-006");
        assertThat(PosterErrorCode.TEMPLATE_NOT_DRAFT.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
    }
}
