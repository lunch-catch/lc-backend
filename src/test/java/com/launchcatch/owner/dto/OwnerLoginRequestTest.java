package com.launchcatch.owner.dto;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class OwnerLoginRequestTest {
    @Test
    void 입력_경계와_개인정보_출력을_검증한다() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (int size : new int[]{10, 20}) {
                assertThat(validator.validate(new OwnerLoginRequest("owner@example.com", "1".repeat(size)))).isEmpty();
            }
            for (int size : new int[]{9, 21}) {
                assertThat(validator.validate(new OwnerLoginRequest("owner@example.com", "1".repeat(size)))).isNotEmpty();
            }
        }
        assertThat(new OwnerLoginRequest("owner@example.com", "password12").toString())
                .doesNotContain("owner@example.com", "password12");
    }
}