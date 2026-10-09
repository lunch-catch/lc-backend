package com.launchcatch.owner.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class OwnerSignupRequestTest {
    @Test
    void 비밀번호_길이_경계는_10자부터_20자까지다() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new OwnerSignupRequest("owner@example.com", "1".repeat(10)))).isEmpty();
            assertThat(validator.validate(new OwnerSignupRequest("owner@example.com", "1".repeat(20)))).isEmpty();
            assertThat(validator.validate(new OwnerSignupRequest("owner@example.com", "1".repeat(9)))).isNotEmpty();
            assertThat(validator.validate(new OwnerSignupRequest("owner@example.com", "1".repeat(21)))).isNotEmpty();
        }
    }

    @Test
    void 요청의_문자열_표현은_비밀번호와_이메일을_노출하지_않는다() {
        assertThat(new OwnerSignupRequest("owner@example.com", "password12").toString())
                .doesNotContain("password12", "owner@example.com");
    }
}