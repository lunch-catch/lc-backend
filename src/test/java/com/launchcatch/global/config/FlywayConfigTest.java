package com.launchcatch.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

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

    @Test
    void 켜져_있는데_비밀번호_해시가_비면_기동을_막는다() {
        Map<String, String> placeholders = Map.of("baseDate", "2026-10-28", "superadminPasswordHash", "$2a$10$x",
                "demoAdminPasswordHash", "", "demoOwnerPasswordHash", "$2a$10$x", "scenarioPasswordHash", "$2a$10$x");
        assertThatThrownBy(() -> FlywayConfig.requireHashes(placeholders))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demoAdminPasswordHash");
    }

    @Test
    void 해시가_모두_있으면_통과한다() {
        Map<String, String> placeholders = Map.of("baseDate", "2026-10-28", "superadminPasswordHash", "$2a$10$x",
                "demoAdminPasswordHash", "$2a$10$x", "demoOwnerPasswordHash", "$2a$10$x", "scenarioPasswordHash", "$2a$10$x");
        assertThatCode(() -> FlywayConfig.requireHashes(placeholders)).doesNotThrowAnyException();
    }
}
