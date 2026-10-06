package com.launchcatch.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/*
 * 기대값은 운영 코드의 규칙을 다시 계산하지 않고 손으로 센 문자열로 박았다.
 */
class PiiMaskerTest {

    @ParameterizedTest
    @CsvSource({
            "abcdef@test.com, ab****@test.com",
            "a@b.com, a***@b.com",
            "abcdef, a*****"
    })
    void 이메일은_앞_두_글자와_도메인만_남긴다(String email, String expected) {
        // when
        String masked = PiiMasker.maskEmail(email);

        // then
        assertThat(masked).isEqualTo(expected);
    }

    @Test
    void 이메일이_null이거나_비어_있으면_그대로_돌려준다() {
        // when, then
        assertThat(PiiMasker.maskEmail(null)).isNull();
        assertThat(PiiMasker.maskEmail("  ")).isEqualTo("  ");
    }

    @ParameterizedTest
    @CsvSource({
            "01012345678, 010****5678",
            "010-1234-5678, 010****5678",
            "0111234567, 011***4567"
    })
    void 전화번호는_앞_세_자리와_뒤_네_자리만_남긴다(String phone, String expected) {
        // when
        String masked = PiiMasker.maskPhone(phone);

        // then
        assertThat(masked).isEqualTo(expected);
    }

    @Test
    void 숫자가_일곱_자리보다_짧은_전화번호는_전부_가린다() {
        // when
        String masked = PiiMasker.maskPhone("12345");

        // then
        assertThat(masked).isEqualTo("*****");
    }

    @Test
    void 전화번호가_null이거나_비어_있으면_그대로_돌려준다() {
        // when, then
        assertThat(PiiMasker.maskPhone(null)).isNull();
        assertThat(PiiMasker.maskPhone("")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "홍길동, 홍*동",
            "남궁민수, 남**수",
            "홍길, 홍*",
            "홍, 홍"
    })
    void 이름은_첫_글자와_끝_글자만_남긴다(String name, String expected) {
        // when
        String masked = PiiMasker.maskName(name);

        // then
        assertThat(masked).isEqualTo(expected);
    }

    @Test
    void 이름이_null이거나_비어_있으면_그대로_돌려준다() {
        // when, then
        assertThat(PiiMasker.maskName(null)).isNull();
        assertThat(PiiMasker.maskName(" ")).isEqualTo(" ");
    }

    @ParameterizedTest
    @CsvSource({
            "1234567890, 2, 2, 12******90",
            "abcdef, 1, 0, a*****",
            "abc, 2, 2, ***",
            "abcd, 2, 2, ****"
    })
    void 범용_마스킹은_앞뒤_지정한_길이만_남긴다(String value, int keepPrefix, int keepSuffix, String expected) {
        // when
        String masked = PiiMasker.maskGeneric(value, keepPrefix, keepSuffix);

        // then
        assertThat(masked).isEqualTo(expected);
    }

    @Test
    void 범용_마스킹은_null이면_null을_돌려준다() {
        // when
        String masked = PiiMasker.maskGeneric(null, 2, 2);

        // then
        assertThat(masked).isNull();
    }

    @Test
    void 값이_있으면_통째로_가린다() {
        // when
        String masked = PiiMasker.redact("eyJhbGciOiJIUzI1NiJ9.payload.signature");

        // then
        assertThat(masked).isEqualTo("***REDACTED***");
    }

    @Test
    void 가릴_값이_없으면_그대로_돌려준다() {
        // when, then
        assertThat(PiiMasker.redact(null)).isNull();
        assertThat(PiiMasker.redact("")).isEmpty();
        assertThat(PiiMasker.redact("   ")).isEqualTo("   ");
    }

    @Test
    void 카카오_회원번호는_앞뒤_두_자리만_남긴다() {
        // when
        String masked = PiiMasker.maskProviderId("1234567890");

        // then
        assertThat(masked).isEqualTo("12******90");
    }

    @Test
    void 제출한_값이_현재_값을_마스킹한_결과와_같으면_화면_표시값을_돌려보낸_것이다() {
        // given
        Function<String, String> masker = PiiMasker::maskName;

        // when
        boolean echo = PiiMasker.isMaskedEchoOf("홍*동", "홍길동", masker);

        // then
        assertThat(echo).isTrue();
    }

    @Test
    void 제출한_값이_마스킹_결과와_다르면_사용자가_바꾼_값이다() {
        // given
        Function<String, String> masker = PiiMasker::maskName;

        // when
        boolean echo = PiiMasker.isMaskedEchoOf("김철수", "홍길동", masker);

        // then
        assertThat(echo).isFalse();
    }

    @Test
    void 제출한_값이나_현재_값이_null이면_표시값을_돌려보낸_것이_아니다() {
        // given
        Function<String, String> masker = PiiMasker::maskName;

        // when, then
        assertThat(PiiMasker.isMaskedEchoOf(null, "홍길동", masker)).isFalse();
        assertThat(PiiMasker.isMaskedEchoOf("홍*동", null, masker)).isFalse();
    }
}
