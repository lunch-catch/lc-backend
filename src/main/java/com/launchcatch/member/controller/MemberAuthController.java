package com.launchcatch.member.controller;

import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.member.dto.KakaoAuthorizeResponse;
import com.launchcatch.member.dto.KakaoLoginRequest;
import com.launchcatch.member.dto.MemberLoginResponse;
import com.launchcatch.member.contract.MemberInfo;
import com.launchcatch.member.contract.MemberQueryService;
import com.launchcatch.member.oauth.KakaoAuthorizationService;
import com.launchcatch.member.service.MemberLoginService;
import com.launchcatch.member.service.MemberTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class MemberAuthController {

    private static final String REFRESH_TOKEN_COOKIE = "refreshToken";

    private final KakaoAuthorizationService kakaoAuthorizationService;
    private final MemberLoginService memberLoginService;
    private final MemberTokenService memberTokenService;
    private final AuthCookieFactory authCookieFactory;
    private final MemberQueryService memberQueryService;

    @GetMapping("/kakao/authorize")
    public ResponseEnvelope<KakaoAuthorizeResponse> authorize(
            @RequestParam(defaultValue = "false") boolean reauth) {
        return ResponseEnvelope.success(new KakaoAuthorizeResponse(
                kakaoAuthorizationService.createAuthorizationUrl(reauth)));
    }

    @PostMapping("/tokens")
    public ResponseEntity<ResponseEnvelope<MemberLoginResponse>> login(
            @Valid @RequestBody KakaoLoginRequest request) {
        MemberLoginService.LoginResult result =
                memberLoginService.login(request.authorizationCode(), request.state());
        MemberLoginResponse response = new MemberLoginResponse(
                result.member().getId(),
                result.member().getNickname(),
                result.newMember(),
                result.onboardingCompleted());
        return tokenResponse(result.tokenPair(), response);
    }

    @PostMapping("/tokens:refresh")
    public ResponseEntity<ResponseEnvelope<MemberLoginResponse>> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID);
        }
        MemberTokenService.TokenPair tokenPair = memberTokenService.reissue(refreshToken);
        MemberInfo member = memberQueryService.findById(tokenPair.memberId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        return tokenResponse(tokenPair, new MemberLoginResponse(
                member.memberId(), member.nickname(), false, member.onboardingCompleted()));
    }

    @DeleteMapping("/tokens")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserDetails user) {
        memberTokenService.logout(user.getId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString())
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie(Role.MEMBER).toString())
                .build();
    }

    private <T> ResponseEntity<ResponseEnvelope<T>> tokenResponse(
            MemberTokenService.TokenPair tokenPair, T response) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.accessTokenCookie(tokenPair.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE,
                        authCookieFactory.refreshTokenCookie(tokenPair.refreshToken(), Role.MEMBER, true).toString())
                .body(ResponseEnvelope.success(response));
    }
}
