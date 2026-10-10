package com.launchcatch.billing.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 토스페이먼츠 접속 설정. 키는 {@code billing.toss.*} 이다.
 *
 * 비밀키가 비어 있으면 기동을 멈춘다. 빈 문자열로 Basic 인증을 보내면 운영에서 모든 승인이 401로 끝나는데,
 * 그 실패는 첫 결제가 들어와야 드러난다.
 *
 * @param baseUrl        API 주소. 테스트 키와 운영 키가 같은 호스트를 쓴다
 * @param secretKey      비밀키. 토스 개발자센터의 API 개별 연동 키
 * @param connectTimeout 연결 타임아웃. 이 안에 못 붙으면 요청이 나가지 않은 것이다
 * @param readTimeout    읽기 타임아웃. 요청을 보낸 뒤 응답을 기다리는 시간이라 넘기면 결과를 알 수 없다
 */
@Validated
@ConfigurationProperties(prefix = "billing.toss")
public record TossProperties(
        @NotBlank @DefaultValue("https://api.tosspayments.com") String baseUrl,
        @NotBlank String secretKey,
        @NotNull @DefaultValue("3s") Duration connectTimeout,
        @NotNull @DefaultValue("10s") Duration readTimeout
) {

    // 레코드의 기본 toString은 비밀키를 그대로 찍는다. 로그나 예외에 이 객체가 실려도 키가 남지 않게 막는다.
    @Override
    public String toString() {
        return "TossProperties[baseUrl=" + baseUrl + ", secretKey=****, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
