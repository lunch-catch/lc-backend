package com.launchcatch.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.auth.Role;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class AdminLoginDtoTest {
    @Test
    void 요청과_내부_결과의_문자열에_비밀번호와_토큰을_노출하지_않는다() {
        var request = new AdminLoginRequest("admin01", "Freshman!2026");
        var response = new AdminLoginResponse(1L, "관리자", Role.ADMIN);
        var result = new AdminLoginResult(response, "raw-access-token", "raw-refresh-token");
        assertThat(request.toString()).doesNotContain(request.password());
        assertThat(result.toString()).doesNotContain(result.accessToken(), result.refreshToken(), "관리자");
    }

    @Test
    void 필수_입력과_DB_컬럼_길이를_검증한다() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new AdminLoginRequest("admin01", "Freshman!2026"))).isEmpty();
            assertThat(validator.validate(new AdminLoginRequest("", ""))).hasSize(2);
            assertThat(validator.validate(new AdminLoginRequest(null, null))).hasSize(2);
            assertThat(validator.validate(new AdminLoginRequest("a".repeat(51), "Freshman!2026"))).hasSize(1);
            assertThat(validator.validate(new AdminLoginRequest("admin01", "p".repeat(73)))).isEmpty();
        }
    }
}
