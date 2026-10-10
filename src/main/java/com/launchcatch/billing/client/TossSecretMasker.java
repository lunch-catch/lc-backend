package com.launchcatch.billing.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/*
 * 로그와 예외 메시지에 들어갈 문자열에서 비밀키를 지운다.
 *
 * 비밀키는 Basic 인증 헤더에서 "비밀키:" 를 Base64로 바꾼 모양으로도 나타날 수 있어
 * 원문과 두 가지 인코딩을 모두 지운다. 토스가 오류 메시지에 값을 되비추는 일은 없지만,
 * 남이 준 문자열을 그대로 로그에 싣지 않는 것이 이 클래스의 목적이다.
 */
final class TossSecretMasker {

    private static final String MASK = "****";

    private final List<String> targets;

    TossSecretMasker(String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            this.targets = List.of();
            return;
        }
        Base64.Encoder encoder = Base64.getEncoder();
        this.targets = List.of(
                encoder.encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8)),
                encoder.encodeToString(secretKey.getBytes(StandardCharsets.UTF_8)),
                secretKey);
    }

    String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = text;
        for (String target : targets) {
            masked = masked.replace(target, MASK);
        }
        return masked;
    }
}
