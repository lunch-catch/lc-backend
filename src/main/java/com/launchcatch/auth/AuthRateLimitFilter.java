package com.launchcatch.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * 로그인과 재발급은 인증 없이 열린 경로라 시도 횟수를 막는 장치가 따로 필요하다.
 *
 * 사용자 로그인에는 비밀번호가 없어(카카오 OIDC) 브루트포스 대상이 아니지만, 요청 한 건이
 * 그대로 카카오 토큰 엔드포인트 호출로 이어진다. 막아 두지 않으면 한 클라이언트가 우리 스레드와
 * 카카오 앱 쿼터를 같이 태워 전체 로그인이 멈춘다. 점주와 관리자는 비밀번호 로그인이라
 * 브루트포스 자체가 대상이다. 로그인 실패 사유를 구분하지 않는 것도 같은 맥락이다.
 *
 * 새 라이브러리를 들이지 않고 이미 있는 인메모리 캐시로 IP 당 고정 윈도우 카운터만 둔다.
 * 분당 10회는 잠정값이라 팀 확인이 필요하다.
 *
 * 캐시 장애 시에는 세지 못하는 것뿐이지 막을 이유가 없어 통과시킨다.
 * JwtAuthenticationFilter 의 커트라인 조회와 같은 이유다. 이 필터 하나 때문에 캐시 블립마다
 * 로그인 전체가 닫히면 안 된다.
 *
 * 피드 조회의 분당 2회 제한은 여기가 아니다. 그쪽은 IP 가 아니라 사용자 ID 기준이고
 * 초과 시 429 와 함께 안내를 돌려줘야 해서 광고 서빙이 자기 카운터로 처리한다.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "authRateLimit:";

    /*
     * 소비자가 셋이라 경로도 셋씩이다(docs/api-spec/README.md 의 경로 절).
     * 점주 회원가입도 열린 경로이지만 지금은 넣지 않는다. 가입은 사업자 검증이 뒤따라
     * 반복 호출의 이득이 적고, 넣으려면 그 경로의 상한을 따로 정해야 한다.
     */
    private static final Set<String> LIMITED_PATHS = Set.of(
            "/v1/auth/tokens", "/v1/auth/tokens:refresh",
            "/v1/owner/auth/tokens", "/v1/owner/auth/tokens:refresh",
            "/v1/admin/auth/tokens", "/v1/admin/auth/tokens:refresh");

    private static final int LIMIT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Pattern SAFE_IP = Pattern.compile("^[0-9a-fA-F.:]{1,45}$");
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = loadRateLimitScript();

    private final StringRedisTemplate redisTemplate;

    private static RedisScript<Long> loadRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auth_rate_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isOverLimit(resolveClientIp(request))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isOverLimit(String ip) {
        String key = KEY_PREFIX + ip;
        try {
            Long count = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT, List.of(key), String.valueOf(WINDOW.toMillis()));
            return count != null && count > LIMIT;
        } catch (DataAccessException e) {
            log.warn("event=RATE_LIMIT_CHECK_FAILED ip={} cause={} 통과시킨다",
                    ip, RedisFailureClassifier.causeLabel(e), e);
            return false;
        }
    }

    /*
     * 로드 밸런서 기본 설정은 실제 클라이언트 IP 를 X-Forwarded-For 의 마지막 항목에 덧붙인다.
     * 이 앱의 8080 은 보안 그룹에서 로드 밸런서에게만 열려 있어야 한다.
     * 그렇지 않으면 직접 접속한 클라이언트가 이 헤더를 위조할 수 있다.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        String[] addresses = forwarded.split(",");
        String candidate = addresses[addresses.length - 1].trim();
        return SAFE_IP.matcher(candidate).matches() ? candidate : request.getRemoteAddr();
    }
}
