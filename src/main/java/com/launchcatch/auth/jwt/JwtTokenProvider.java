package com.launchcatch.auth.jwt;

import com.launchcatch.auth.Role;
import com.launchcatch.global.config.ClockConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/*
 * Access Token 생성과 파싱, 검증을 맡는다. 역할 넷이 공용으로 쓰므로 auth 모듈 소속이다(규칙 5).
 *
 * Refresh Token 은 이 클래스가 만들지 않는다. Opaque 토큰이라 OpaqueTokenGenerator 가 만든다.
 * 그런데 수명 정책은 여기 남긴다. Access 와 Refresh 의 수명을 한 곳에서 들고 있는 것이 이
 * 클래스의 역할이기 때문이다.
 *
 * Refresh 수명이 역할마다 다르다. 관리자 1일, 점주와 사용자 14일이다. 회원은 자동 로그인
 * 선택지를 두지 않고 항상 14일 persistent cookie를 발급한다. 옮겨온 쪽은 수명이 하나뿐이어서
 * 값을 단일 필드로 들고 있었다.
 *
 * Clock 을 주입받아 발급 시각을 정한다. 시험이 Clock.fixed(...) 로 "지금" 을 고정해 만료 경계를
 * 결정적으로 재현할 수 있다. 영업일 경계가 걸린 프로젝트라 시각을 직접 읽는 자리를 남기지 않는다.
 */
@Component
public class JwtTokenProvider {

    private static final String ROLE_CLAIM = "role";
    // 각 도메인이 제공한 추가 정책을 역할별로 보관한다.
    private Map<Role, AccessTokenPolicy> policies = Map.of();

    private final SecretKey secretKey;
    private final Clock clock;
    private final long accessTokenValidityMs;
    private final Map<Role, Long> refreshTokenValidityMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-ms}") long accessTokenValidityMs,
            @Value("${jwt.refresh.admin-validity-ms}") long adminRefreshValidityMs,
            @Value("${jwt.refresh.owner-validity-ms}") long ownerRefreshValidityMs,
            @Value("${jwt.refresh.member-validity-ms}") long memberRefreshValidityMs,
            Clock clock
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.clock = clock;
        this.accessTokenValidityMs = accessTokenValidityMs;

        Map<Role, Long> validity = new EnumMap<>(Role.class);
        validity.put(Role.SUPER_ADMIN, adminRefreshValidityMs);
        validity.put(Role.ADMIN, adminRefreshValidityMs);
        validity.put(Role.OWNER, ownerRefreshValidityMs);
        validity.put(Role.MEMBER, memberRefreshValidityMs);
        this.refreshTokenValidityMs = Map.copyOf(validity);
    }

    // 도메인의 정책 구현체를 주입받아 등록하며, 같은 역할에 정책이 두 개면 시작을 중단한다.
    @Autowired
    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-ms}") long accessTokenValidityMs,
            @Value("${jwt.refresh.admin-validity-ms}") long adminRefreshValidityMs,
            @Value("${jwt.refresh.owner-validity-ms}") long ownerRefreshValidityMs,
            @Value("${jwt.refresh.member-validity-ms}") long memberRefreshValidityMs,
            Clock clock, List<AccessTokenPolicy> sources) {
        this(secret, accessTokenValidityMs, adminRefreshValidityMs, ownerRefreshValidityMs, memberRefreshValidityMs, clock);
        Map<Role, AccessTokenPolicy> registered = new EnumMap<>(Role.class);
        for (AccessTokenPolicy source : sources) {
            if (registered.put(source.role(), source) != null) {
                throw new IllegalStateException("duplicate AccessTokenPolicy role: " + source.role());
            }
        }
        policies = Map.copyOf(registered);
    }

    // 추가 정책이 없는 역할은 기존 공통 JWT 검증만 적용한다.
    public boolean validateAdditionalClaims(String token) {
        Claims claims = parseClaims(token);
        AccessTokenPolicy policy = policies.get(Role.from(claims.get(ROLE_CLAIM, String.class)));
        return policy == null || policy.isValid(Long.valueOf(claims.getSubject()), claims);
    }

    public String createAccessToken(Long id, Role role) {
        Instant now = clock.instant();
        var builder = Jwts.builder();
        AccessTokenPolicy policy = policies.get(role);
        if (policy != null) {
            // 도메인이 제공한 추가 정보도 공통 정보와 함께 JWT에 서명한다.
            builder.claims(policy.additionalClaims(id));
        }
        return builder.subject(String.valueOf(id))
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenValidityMs)))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /** 목록에 없는 값이면 null 이다. 근거는 Role.from 의 주석에 있다. */
    public Role getRole(String token) {
        return Role.from(parseClaims(token).get(ROLE_CLAIM, String.class));
    }

    public LocalDateTime getIssuedAt(String token) {
        Date issuedAt = parseClaims(token).getIssuedAt();
        return issuedAt == null ? null : LocalDateTime.ofInstant(issuedAt.toInstant(), ClockConfig.ZONE);
    }

    public long getAccessTokenValidityMs() { return accessTokenValidityMs; }

    public long refreshTokenValidityMs(Role role) { return refreshTokenValidityMs.get(role); }
}
