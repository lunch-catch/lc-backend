package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FlywayConfigTest {

    @Test
    void 끝의_슬래시를_모두_뗀다() {
        assertThat(FlywayConfig.withoutTrailingSlash("https://cdn.dev.lunchcatch.com//"))
                .isEqualTo("https://cdn.dev.lunchcatch.com");
    }

    @Test
    void 슬래시가_없으면_그대로_둔다() {
        assertThat(FlywayConfig.withoutTrailingSlash("https://cdn.dev.lunchcatch.com"))
                .isEqualTo("https://cdn.dev.lunchcatch.com");
    }

    @Test
    void 빈_값은_빈_값이다() {
        assertThat(FlywayConfig.withoutTrailingSlash("")).isEmpty();
    }
}
