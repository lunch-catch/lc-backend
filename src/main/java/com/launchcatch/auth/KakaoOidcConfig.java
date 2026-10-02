package com.launchcatch.auth;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;

/*
 * 프론트 콜백형 로그인 흐름이라 Spring Security 의 oauth2Login() 필터 체인을 쓰지 않는다.
 * 카카오가 프론트 주소로 code 를 붙여 리다이렉트하면 프론트가 그것을 받아 백엔드로 넘긴다.
 * 그 필터를 안 쓰므로 id_token 의 서명과 클레임 검증기를 직접 세워야 한다. 다만 검증 로직을
 * 손으로 짜지는 않고, Spring Security 가 원래 쓰던 NimbusJwtDecoder 를 필터 체인 밖에서
 * 단독 빈으로 구성해 재사용한다.
 */
//
// client-id/token-uri/jwks-uri 같은 값은 여기서 새로 안 적고 ClientRegistrationRepository
// (application.yml의 spring.security.oauth2.client.* 설정을 스프링이 이미 파싱해둔 것)에서
// 그대로 가져다 쓴다 — 중복 설정을 피하기 위함이다. issuer-uri만 줘도 스프링이 기동 시 카카오의
// OIDC discovery 문서(/.well-known/openid-configuration)를 읽어 token-uri/jwks-uri를
// 자동으로 채워 넣는다.
@Configuration
public class KakaoOidcConfig {

    private static final String REGISTRATION_ID = "kakao";

    // ClientRegistration.ProviderDetails에도 issuer-uri를 꺼내는 방법이 있지만, application.yml에
    // 이미 우리가 적어둔 값(spring.security.oauth2.client.provider.kakao.issuer-uri)을 그대로
    // 주입받는 편이 API 버전에 덜 민감하다.
    @Value("${spring.security.oauth2.client.provider.kakao.issuer-uri}")
    private String kakaoIssuerUri;

    @Bean
    public JwtDecoder kakaoJwtDecoder(ClientRegistrationRepository clientRegistrationRepository) {
        ClientRegistration kakao = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        String jwkSetUri = kakao.getProviderDetails().getJwkSetUri();
        String clientId = kakao.getClientId();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        // iss/exp는 JwtValidators가 기본으로 검증해준다. aud(우리 client-id로 발급된 토큰이 맞는지)는
        // 여기서 따로 추가한다 — 안 하면 다른 앱용으로 발급된 id_token도 통과해버린다.
        // nonce는 클레임 단위 정적 검증기로 표현할 수 없어(요청마다 기대값이 다름) 여기서 안 하고,
        // 호출하는 쪽(카카오 로그인 서비스)이 Redis에 저장해둔 값과 직접 비교한다.
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(kakaoIssuerUri);
        OAuth2TokenValidator<Jwt> withAudience =
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(clientId));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));

        return decoder;
    }

    /**
     * 벤더 계층. 카카오 OIDC 토큰교환과 로그아웃, 연결 해제 호출 전용.
     * 공통 타임아웃/풀/필터는 위 defaultExternalApiCustomizer가 이미 적용한 builder를 받아서 시작하므로
     * 여기선 카카오 고유의 것만 얹는다 — 지금은 얹을 게 없어서 build()만 호출한다.
     *
     * 카카오가 인증(kauth.kakao.com)과 API(kapi.kakao.com) 호스트
     * 자체를 분리해놔서 WebClient 하나에 baseUrl 하나로 못 묶는다.
     * 그래서 각 호출부가 지금처럼 절대경로 URI를 직접 준다.
     *
     * 나중에 카카오만 다른 속성이 필요해지면(예: logout/unlink 공통 Admin Key 헤더를 매번
     * 반복해서 붙이는 대신 여기 기본값으로 박아두거나, 로그인 흐름 UX 때문에 토큰교환만 공통
     * 5초보다 짧은 타임아웃을 원한다거나) 이 메서드의 체인을 늘리면 된다. 예:
     *
     *   return builder
     *           .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
     *           .clientConnector(new ReactorClientHttpConnector(
     *                   HttpClient.create().responseTimeout(Duration.ofSeconds(3))))
     *           .build();
     *
     * clientConnector를 여기서 다시 지정하면 공통 커스터마이저가 걸어둔 connector(타임아웃/풀)만
     * 덮어쓰는 것이고, 트레이싱/로깅 필터는 builder에 이미 filter()로 붙어있어서 그대로
     * 유지된다 — 필터까지 다시 정의할 필요는 없다.
     */
    @Bean
    public WebClient kakaoApiWebClient(WebClient.Builder builder) {
        return builder.build();
    }
}
