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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "회원 인증",
        description = "카카오 OIDC 로그인, Refresh Token 재발급, 로그아웃 API입니다. 성공 응답의 토큰은 본문이 아닌 HttpOnly 쿠키로만 설정됩니다."
)
public class MemberAuthController {

    private static final String REFRESH_TOKEN_COOKIE = "refreshToken";

    private final KakaoAuthorizationService kakaoAuthorizationService;
    private final MemberLoginService memberLoginService;
    private final MemberTokenService memberTokenService;
    private final AuthCookieFactory authCookieFactory;
    private final MemberQueryService memberQueryService;

    @GetMapping("/kakao/authorize")
    @Operation(
            summary = "카카오 인가 URL 발급",
            description = "카카오 로그인 화면으로 이동할 URL을 발급합니다. 서버는 일회용 state와 OIDC nonce를 Redis에 5분간 저장하고 URL에 포함합니다. 프론트는 authorizationUrl에서 state를 추출해 현재 탭의 sessionStorage에 저장한 뒤 URL 전체로 이동합니다. reauth=true는 탈퇴 재인증용이며 카카오에 prompt=login을 요청합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "카카오 인가 URL 발급 성공")
    })
    public ResponseEnvelope<KakaoAuthorizeResponse> authorize(
            @Parameter(
                    description = "true면 카카오 로그인 화면을 다시 표시해 재인증을 요청합니다.",
                    schema = @Schema(type = "boolean", defaultValue = "false"),
                    example = "false"
            )
            @RequestParam(defaultValue = "false") boolean reauth) {
        return ResponseEnvelope.success(new KakaoAuthorizeResponse(
                kakaoAuthorizationService.createAuthorizationUrl(reauth)));
    }

    @PostMapping("/tokens")
    @Operation(
            summary = "카카오 인가 코드로 로그인 또는 가입",
            description = "카카오 콜백의 authorizationCode와 state를 교환해 로그인합니다. 서버는 state를 한 번만 소비하고 ID Token의 서명, 발급자, 대상 앱, 만료, nonce를 검증합니다. 최초 가입 또는 탈퇴 후 재가입이면 newMember가 true입니다. Access Token과 14일 Refresh Token은 HttpOnly, SameSite=Strict 쿠키로 설정되며 응답 본문에는 토큰 문자열을 포함하지 않습니다."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "카카오 콜백에서 받은 인가 코드와 state입니다.",
            content = @Content(
                    schema = @Schema(implementation = KakaoLoginRequest.class),
                    examples = @ExampleObject(value = """
                            {
                              "authorizationCode": "kakao-authorization-code",
                              "state": "one-time-state"
                            }
                            """)
            )
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 또는 가입 성공. accessToken과 refreshToken 쿠키가 설정됩니다."),
            @ApiResponse(responseCode = "400", description = "COMMON-002: authorizationCode 또는 state가 비어 있습니다."),
            @ApiResponse(responseCode = "401", description = "AUTH-007: state가 만료 또는 재사용되었거나 카카오 인가 코드 또는 ID Token 검증에 실패했습니다."),
            @ApiResponse(responseCode = "403", description = "MEMBER-003: 정지 이력이 있는 회원번호는 재가입할 수 없습니다."),
            @ApiResponse(responseCode = "503", description = "AUTH-002: DB Refresh Token 해시 백업 저장에 실패했습니다.")
    })
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
    @Operation(
            summary = "회원 토큰 재발급",
            description = "refreshToken HttpOnly 쿠키로 Access Token과 Refresh Token을 함께 교체합니다. 새 Refresh Token도 14일 persistent cookie로 설정됩니다. 이미 회전된 Refresh Token을 다시 사용하면 해당 회원의 Refresh Token을 모두 폐기합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 재발급 성공. 새 accessToken과 refreshToken 쿠키가 설정됩니다."),
            @ApiResponse(responseCode = "401", description = "AUTH-003: Refresh Token이 없거나 유효하지 않습니다. AUTH-004: 이미 사용한 Refresh Token이 재사용되어 모든 Refresh Token을 폐기했습니다."),
            @ApiResponse(responseCode = "503", description = "AUTH-002: Refresh Token 저장소 처리에 실패했습니다.")
    })
    public ResponseEntity<ResponseEnvelope<MemberLoginResponse>> refresh(
            @Parameter(
                    name = REFRESH_TOKEN_COOKIE,
                    in = ParameterIn.COOKIE,
                    required = true,
                    description = "로그인 성공 시 설정된 회원 Refresh Token HttpOnly 쿠키입니다.",
                    schema = @Schema(type = "string")
            )
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
    @Operation(
            summary = "회원 로그아웃",
            description = "현재 회원의 Refresh Token을 폐기하고 기존 Access Token을 무효화합니다. 응답은 accessToken과 refreshToken 쿠키를 만료시킵니다. DB 커밋 후 카카오 로그아웃도 best-effort로 요청합니다."
    )
    @SecurityRequirement(name = "memberAccessToken")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "로그아웃 성공. accessToken과 refreshToken 쿠키가 만료됩니다."),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 회원 Access Token이 없거나 유효하지 않습니다."),
            @ApiResponse(responseCode = "503", description = "AUTH-002: Refresh Token 폐기에 실패했습니다.")
    })
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
