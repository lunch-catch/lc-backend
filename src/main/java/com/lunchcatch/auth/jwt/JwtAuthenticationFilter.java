package com.lunchcatch.auth.jwt;

import com.lunchcatch.auth.AuthCookieNames;
import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.contract.AccessTokenValidationPolicy;
import com.lunchcatch.auth.exception.AuthException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/*
 * 역할 넷의 토큰을 한 필터에서 처리한다. 체인을 역할마다 쪼개지 않고 role 클레임으로 주체를
 * 세운 뒤, 인가는 각 체인의 경로와 권한 선언이 맡는다.
 *
 * 토큰은 쿠키에서만 읽는다. docs/api-spec/api-spec.md 가 "클라이언트는 토큰 값을 읽거나 Authorization
 * 헤더에 싣지 않는다" 고 못 박았다. 옮겨온 쪽은 Swagger 수동 시험용으로 헤더를 함께 받았는데,
 * 받아 주는 경로가 열려 있으면 명세가 금지한 사용법이 조용히 자리를 잡는다. 로컬에서 헤더로
 * 찔러 볼 일이 생기면 그때 프로필로 가르는 편이 낫다.
 *
 * 인증 실패 시 여기서 JSON 을 직접 쓰지 않고 인증 없이 다음 필터로 넘긴다. ApiSecurityDefaults 가
 * 필터 예외를 HandlerExceptionResolver 로 GlobalExceptionHandler 에 위임하므로 별도의
 * EntryPoint 와 AccessDeniedHandler 클래스가 필요 없다.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenCutoffVerifier cutoffVerifier;
    private final List<AccessTokenValidationPolicy> validationPolicies;
    private final HandlerExceptionResolver exceptionResolver;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
            AccessTokenValidAfterRepository accessTokenValidAfterRepository,
            List<AccessTokenValidationPolicy> validationPolicies,
            HandlerExceptionResolver exceptionResolver) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.cutoffVerifier = new AccessTokenCutoffVerifier(accessTokenValidAfterRepository);
        this.validationPolicies = List.copyOf(validationPolicies);
        this.exceptionResolver = Objects.requireNonNull(exceptionResolver, "exceptionResolver");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = resolveToken(request);

        if (token == null || !jwtTokenProvider.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        Long id = jwtTokenProvider.getId(token);
        Role role = jwtTokenProvider.getRole(token);

        if (role == null) {
            filterChain.doFilter(request, response);
            return;
        }

        LocalDateTime issuedAt = jwtTokenProvider.getIssuedAt(token);
        Map<String, Object> additionalClaims = Objects.requireNonNullElse(
                jwtTokenProvider.getAdditionalClaims(token), Map.of());
        /*
         * 이 역할을 담당하는 도메인 정책으로 토큰을 검증한다.
         * 등록된 정책이 없으면 기존 발급 시각 기준으로 검증한다.
         */
        boolean valid;
        try {
            valid = validationPolicies.stream().filter(policy -> policy.supports(role)).findFirst()
                    .map(policy -> policy.isValid(role, id, issuedAt, additionalClaims, CutoffPolicy.LENIENT))
                    .orElseGet(() -> cutoffVerifier.isValidAfter(role, id, issuedAt, CutoffPolicy.LENIENT));
        } catch (AuthException e) {
            /*
             * 정책이 세션 저장소를 읽지 못하면 AUTH-002 를 던진다(#1226).
             * 이 필터는 ExceptionTranslationFilter 앞에 있어 예외를 그대로 던지면 봉투 밖으로 나간다.
             * 그래서 MVC 예외 처리에 직접 넘겨 503 과 Retry-After 를 받고 체인을 멈춘다.
             */
            exceptionResolver.resolveException(request, response, null, e);
            return;
        }
        if (!valid) {
            filterChain.doFilter(request, response);
            return;
        }

        // 검증한 추가 정보를 인증 사용자에 담아 컨트롤러에서도 사용할 수 있게 한다.
        CustomUserDetails userDetails = new CustomUserDetails(id, role, issuedAt, additionalClaims);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    // 요청 경로의 역할 쿠키만 읽는다. 다른 역할의 쿠키가 함께 실려도 쓰지 않는다(#841)
    private String resolveToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        String cookieName = AuthCookieNames.accessTokenCookieNameForPath(
                request.getRequestURI().substring(request.getContextPath().length()));
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
