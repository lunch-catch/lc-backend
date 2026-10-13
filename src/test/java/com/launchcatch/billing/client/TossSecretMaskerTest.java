package com.launchcatch.billing.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TossSecretMaskerTest {

    private static final String SECRET = "test_sk_SECRET_VALUE_1234";

    @Test
    void 비밀키_원문을_지운다() {
        TossSecretMasker masker = new TossSecretMasker(SECRET);

        assertThat(masker.mask("key=" + SECRET + " end")).isEqualTo("key=**** end");
    }

    @Test
    void Basic_인증_헤더에_쓰이는_Base64_형태도_지운다() {
        TossSecretMasker masker = new TossSecretMasker(SECRET);
        String header = Base64.getEncoder().encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8));

        assertThat(masker.mask("Authorization: Basic " + header)).isEqualTo("Authorization: Basic ****");
    }

    @Test
    void 비밀키가_없거나_빈_문자열이면_문자열을_그대로_둔다() {
        assertThat(new TossSecretMasker(null).mask("abc")).isEqualTo("abc");
        assertThat(new TossSecretMasker(" ").mask("abc")).isEqualTo("abc");
    }

    @Test
    void 지울_문자열이_null_이면_null_을_돌려준다() {
        assertThat(new TossSecretMasker(SECRET).mask(null)).isNull();
    }
}
