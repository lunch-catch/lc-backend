package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// 기동 때 카운터가 0 으로 있어야 경보가 첫 사건의 증가를 센다(#1027)
class MemberCounterRegistrationTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void removeRegistry() {
        Metrics.removeRegistry(registry);
    }

    @Test
    void 재가입_계정의_모호한_연결_해제_통보_카운터를_0으로_만든다() {
        Metrics.addRegistry(registry);

        new MemberWithdrawalService(null, null, null, null, null, null, null, null, null).registerCounters();

        assertThat(registry.get("member.unlink.webhook.ambiguous").counter().count()).isZero();
    }
}
