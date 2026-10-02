package com.launchcatch.auth.jwt;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.RedisFailureClassifier;
import com.launchcatch.auth.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * 역할 넷의 토큰을 한 필터에서 처리한다. 체인을 역할마다 쪼개지 않고 role 클레임으로 주체를
 * 세운 뒤, 인가는 각 체인의 경로와 권한 선언이 맡는다.
 *
 * 토큰은 쿠키에서만 읽는다. docs/api-spec/README.md 가 "클라이언트는 토큰 값을 읽거나 Authorization
 * 헤더에 싣지 않는다" 고 못 박았다. 옮겨온 쪽은 Swagger 수동 시험용으로 헤더를 함께 받았는데,
 * 받아 주는 경로가 열려 있으면 명세가 금지한 사용법이 조용히 자리를 잡는다. 로컬에서 헤더로
 * 찔러 볼 일이 생기면 그때 프로필로 가르는 편이 낫다.
 *
 * 인증 실패 시 여기서 JSON 을 직접 쓰지 않고 인증 없이 다음 필터로 넘긴다. ApiSecurityDefaults 가
 * 필터 예외를 HandlerExceptionResolver 로 GlobalExceptionHandler 에 위임하므로 별도의
 * EntryPoint 와 AccessDeniedHandler 클래스가 필요 없다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String ACCESS_TOKEN_COOKIE_NAME = "accessToken";

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;

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

        if (!isValidAfterCutoff(role, id, jwtTokenProvider.getIssuedAt(token))) {
            filterChain.doFilter(request, response);
            return;
        }

        CustomUserDetails userDetails = new CustomUserDetails(id, role);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    /*
     * 캐시가 죽으면 통과시킨다.
     * 커트라인 조회가 안 된다고 인증 전체를 막으면 캐시 블립 한 번에 서비스가 통째로 닫힌다.
     * 잃는 것은 "로그아웃한 Access 가 남은 수명만큼 더 사는" 창이고, 그 창은 최대 30분이다.
     */
    private boolean isValidAfterCutoff(Role role, Long id, LocalDateTime issuedAt) {
        if (issuedAt == null) {
            return true;
        }
        try {
            return accessTokenValidAfterRepository.isValidAfter(role, id, issuedAt);
        } catch (DataAccessException e) {
            log.warn("event=ACCESS_TOKEN_VALID_AFTER_CHECK_FAILED role={} id={} cause={} 통과시킨다",
                    role, id, RedisFailureClassifier.causeLabel(e), e);
            return true;
        }
    }

    private String resolveToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (ACCESS_TOKEN_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
