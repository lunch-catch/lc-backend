package com.launchcatch.member.oauth;

import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.member.client.KakaoTokenClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoIdTokenExchanger {

    private static final String REGISTRATION_ID = "kakao";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final KakaoLoginStateRepository kakaoLoginStateRepository;
    private final KakaoTokenClient kakaoTokenClient;
    private final JwtDecoder kakaoJwtDecoder;

    public KakaoIdentity exchange(String authorizationCode, String state) {
        String expectedNonce = kakaoLoginStateRepository.consume(state);
        if (expectedNonce == null) {
            throw new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED);
        }

        ClientRegistration kakao = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        try {
            KakaoTokenResponse tokenResponse = kakaoTokenClient.exchange(kakao, authorizationCode);
            Jwt idToken = kakaoJwtDecoder.decode(tokenResponse.idToken());
            if (!expectedNonce.equals(idToken.getClaimAsString("nonce"))) {
                throw new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED);
            }
            return KakaoIdentity.from(idToken);
        } catch (AuthException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException | CallNotPermittedException e) {
            log.warn("event=KAKAO_ID_TOKEN_VERIFICATION_FAILED", e);
            throw new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED, e);
        }
    }
}
