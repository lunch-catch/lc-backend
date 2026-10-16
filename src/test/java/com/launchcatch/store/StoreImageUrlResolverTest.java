package com.launchcatch.store;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoreImageUrlResolverTest {
    @Test
    void 이미지가_없으면_CDN_설정과_무관하게_null이다() {
        assertThat(new StoreImageUrlResolver("").resolve(null)).isNull();
    }

    @Test
    void 경로_구분자를_보존하고_객체_키의_특수문자를_인코딩한다() {
        var resolver = new StoreImageUrlResolver(" https://cdn.example.com/media/// ");

        assertThat(resolver.resolve("stores/a b?#%.png"))
                .isEqualTo("https://cdn.example.com/media/stores/a%20b%3F%23%25.png");
    }

    @Test
    void 이미지가_있는데_CDN_설정이_없으면_실패한다() {
        assertThatIllegalStateException().isThrownBy(() -> new StoreImageUrlResolver("").resolve("logo.png"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/media", "ftp://cdn.example.com", "https://cdn.example.com?q=1",
            "https://cdn.example.com/#image", "https://user:password@cdn.example.com", "https://"})
    void 공개_이미지_기준_주소가_아니면_거절한다(String baseUrl) {
        assertThatIllegalArgumentException().isThrownBy(() -> new StoreImageUrlResolver(baseUrl));
    }
}