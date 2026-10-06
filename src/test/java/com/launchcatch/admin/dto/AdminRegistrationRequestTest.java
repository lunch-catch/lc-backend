package com.launchcatch.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AdminRegistrationRequestTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() { FACTORY.close(); }

    @Test
    void 정상_요청은_유효하다() {
        assertThat(VALIDATOR.validate(request("Initial123!"))).isEmpty();
        assertThat(VALIDATOR.validate(request("Aa1!" + "a".repeat(68)))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Short1!", "lowercase123!", "UPPERCASE123!", "NoNumbers!!!", "NoSpecial123"})
    void 비밀번호_필수값과_복잡도를_검증한다(String password) {
        assertThat(VALIDATOR.validate(request(password))).isNotEmpty();
    }

    @Test
    void BCrypt_72바이트_초과를_검증한다() {
        assertThat(VALIDATOR.validate(request("Aa1!" + "a".repeat(69)))).isNotEmpty();
        assertThat(VALIDATOR.validate(request("Aa1!" + "가".repeat(23)))).isNotEmpty();
        assertThat(VALIDATOR.validate(request("Aa1!" + "가".repeat(22)))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"OWNER", "MEMBER", "admin", "INVALID"})
    void 관리자_권한만_허용한다(String role) {
        assertThat(VALIDATOR.validate(new AdminRegistrationRequest("admin01", "Initial123!", "홍길동", role)))
                .isNotEmpty();
    }

    @Test
    void ID와_이름의_형식을_검증한다() {
        assertThat(VALIDATOR.validate(new AdminRegistrationRequest("bad id", "Initial123!", "홍길동", "ADMIN")))
                .isNotEmpty();
        assertThat(VALIDATOR.validate(new AdminRegistrationRequest("a".repeat(51), "Initial123!", "홍길동", "ADMIN")))
                .isNotEmpty();
        assertThat(VALIDATOR.validate(new AdminRegistrationRequest("admin01", "Initial123!", "홍길동1", "ADMIN")))
                .isNotEmpty();
        assertThat(VALIDATOR.validate(new AdminRegistrationRequest("admin01", "Initial123!", "가".repeat(51), "ADMIN")))
                .isNotEmpty();
    }

    @Test
    void 요청_문자열에_비밀번호와_이름을_노출하지_않는다() {
        assertThat(request("Initial123!").toString()).doesNotContain("Initial123!", "홍길동");
    }

    private static AdminRegistrationRequest request(String password) {
        return new AdminRegistrationRequest("admin01", password, "홍길동", "ADMIN");
    }
}