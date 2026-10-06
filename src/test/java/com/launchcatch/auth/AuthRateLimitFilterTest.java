package com.launchcatch.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/*
 * 로그인 속도 제한이 막을 때 클라이언트에게 "언제 다시 와라" 를 주는지 본다.
 *
 * 그 값이 없으면 막힌 클라이언트가 곧바로 다시 누른다. 막으려던 요청이 그대로 다시 몰려서
 * 제한을 두는 이유가 사라진다. api-spec/README.md 의 429 가 Retry-After 를 요구한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthRateLimitFilterTest {

    private static final String LIMITED = "/v1/admin/auth/tokens";
    private static final String IP = "1.2.3.4";
    private static final long LIMIT = 10;
    private static final long WINDOW_SECONDS = 60;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private FilterChain chain;

    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthRateLimitFilter(redisTemplate);
    }

    private void countReturns(long count) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(count);
    }

    private MockHttpServletResponse run(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(IP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("한도 안이면 통과시킨다")
    void 한도_안() throws Exception {
        countReturns(LIMIT);

        MockHttpServletResponse response = run("POST", LIMITED);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("한도를 넘으면 429 로 막는다")
    void 한도_초과() throws Exception {
        countReturns(LIMIT + 1);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(17L);

        MockHttpServletResponse response = run("POST", LIMITED);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        verify(chain, never()).doFilter(any(), any());
    }

    /*
     * 윈도우 길이가 아니라 남은 수명을 준다.
     * 고정 윈도우라 끝까지 17초 남았는데 60초를 기다리라고 하면 그만큼 쓸 수 있는 시간을 버린다.
     */
    @Test
    @DisplayName("Retry-After 는 카운터의 남은 수명이다")
    void 남은_수명을_준다() throws Exception {
        countReturns(LIMIT + 1);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(17L);

        MockHttpServletResponse response = run("POST", LIMITED);

        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("17");
    }

    /*
     * 수명을 읽지 못해도 헤더는 준다.
     * 빼 버리면 클라이언트가 곧바로 다시 눌러 막으려던 요청이 다시 몰린다.
     */
    @Test
    @DisplayName("수명을 읽지 못하면 윈도우 길이로 답한다")
    void 수명을_모르면_윈도우_길이() throws Exception {
        countReturns(LIMIT + 1);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenThrow(new QueryTimeoutException("응답이 없다"));

        MockHttpServletResponse response = run("POST", LIMITED);

        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER))
                .isEqualTo(String.valueOf(WINDOW_SECONDS));
    }

    /** 수명이 없는 키(-1)나 사라진 키(-2)도 같은 자리로 떨어진다. */
    @Test
    @DisplayName("수명이 남아 있지 않으면 윈도우 길이로 답한다")
    void 수명이_없으면_윈도우_길이() throws Exception {
        countReturns(LIMIT + 1);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(-2L);

        MockHttpServletResponse response = run("POST", LIMITED);

        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER))
                .isEqualTo(String.valueOf(WINDOW_SECONDS));
    }

    /*
     * 캐시가 죽으면 세지 못하는 것뿐이라 통과시킨다.
     * 이 필터 하나 때문에 캐시 블립마다 로그인 전체가 닫히면 안 된다.
     */
    @Test
    @DisplayName("캐시 장애면 통과시킨다")
    void 캐시_장애() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new QueryTimeoutException("응답이 없다"));

        MockHttpServletResponse response = run("POST", LIMITED);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("대상 경로가 아니면 세지 않는다")
    void 대상이_아닌_경로() throws Exception {
        run("POST", "/v1/campaigns");

        verifyNoInteractions(redisTemplate);
        verify(chain).doFilter(any(), any());
    }

    /** 로그아웃은 DELETE 라 대상이 아니다. */
    @Test
    @DisplayName("POST 가 아니면 세지 않는다")
    void 대상이_아닌_메서드() throws Exception {
        run("DELETE", LIMITED);

        verifyNoInteractions(redisTemplate);
        verify(chain).doFilter(any(), any());
    }
}
