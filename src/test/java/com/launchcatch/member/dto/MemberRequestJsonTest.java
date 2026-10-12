package com.launchcatch.member.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/*
 * 요청 본문을 읽는 단계의 거절을 고정한다. 애플리케이션이 실제로 쓰는 Boot 의 매퍼로 읽는다.
 * 읽기 실패가 400 COMMON-003 으로 바뀌는 것은 GlobalExceptionHandler 가 맡는다.
 */
class MemberRequestJsonTest {

    private final JsonMapper mapper = bootMapper();

    // 애플리케이션이 실제로 쓰는 매퍼다. 직접 만든 매퍼로는 Boot 의 기본 설정을 검증하지 못한다
    private static JsonMapper bootMapper() {
        AtomicReference<JsonMapper> holder = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> holder.set(context.getBean(JsonMapper.class)));
        return holder.get();
    }

    @Test
    @DisplayName("온보딩의 성별이 허용값 밖이면 읽지 못한다")
    void 온보딩의_성별이_허용값_밖이면_읽지_못한다() {
        String json = "{\"gender\":\"UNKNOWN\",\"ageGroup\":\"AGE_20S\",\"locationOptIn\":true,\"notificationOptIn\":true}";

        assertThatThrownBy(() -> mapper.readValue(json, MemberOnboardingRequest.class))
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    @DisplayName("온보딩의 연령대가 허용값 밖이면 읽지 못한다")
    void 온보딩의_연령대가_허용값_밖이면_읽지_못한다() {
        String json = "{\"gender\":\"MALE\",\"ageGroup\":\"AGE_10S\",\"locationOptIn\":true,\"notificationOptIn\":true}";

        assertThatThrownBy(() -> mapper.readValue(json, MemberOnboardingRequest.class))
                .hasMessageContaining("AGE_10S");
    }

    @Test
    @DisplayName("알려진 필드만 보내면 그대로 읽는다")
    void 알려진_필드만_보내면_그대로_읽는다() {
        MemberUpdateRequest request = mapper.readValue("{\"nickname\":\"가\"}", MemberUpdateRequest.class);

        assertThat(request.nickname()).isEqualTo("가");
        assertThat(request.notificationOptIn()).isNull();
    }
}
