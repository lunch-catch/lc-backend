package com.lunchcatch.campaign.template.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 템플릿 이름은 앞뒤 공백을 자른 값으로 검증한다(#880). 컨트롤러까지 올리지 않고 검증기만 돌린다.
 */
class TemplateNameRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    @DisplayName("이름 수정 요청은 앞뒤 공백을 잘라 담고, 공백을 뺀 길이가 100자면 통과한다")
    void 이름_수정은_공백을_자른_길이로_검증한다() {
        TemplateRenameRequest request = new TemplateRenameRequest("  " + "가".repeat(100) + "  ");

        assertThat(request.name()).hasSize(100);
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("이름 수정 요청은 공백을 뺀 길이가 100자를 넘거나 공백뿐이거나 없으면 거부한다")
    void 이름_수정은_잘못된_이름을_거부한다() {
        assertThat(VALIDATOR.validate(new TemplateRenameRequest("가".repeat(101)))).hasSize(1);
        assertThat(VALIDATOR.validate(new TemplateRenameRequest("   "))).hasSize(1);
        assertThat(VALIDATOR.validate(new TemplateRenameRequest(null))).hasSize(1);
    }

    @Test
    @DisplayName("템플릿 생성 요청도 이름의 앞뒤 공백을 잘라 검증한다")
    void 생성도_공백을_자른_길이로_검증한다() {
        TemplateCreateRequest request =
                new TemplateCreateRequest(" " + "가".repeat(100) + " ", "가을 느낌으로 만들어줘", "req-1");

        assertThat(request.name()).hasSize(100);
        assertThat(VALIDATOR.validate(request)).isEmpty();
        assertThat(VALIDATOR.validate(new TemplateCreateRequest("가".repeat(101), "가을 느낌으로 만들어줘", "req-1")))
                .hasSize(1);
    }
}
