package com.lunchcatch.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.lunchcatch.auth.opaque.RefreshTokenBackupStore;
import com.lunchcatch.auth.opaque.RefreshTokenRepository;
import com.lunchcatch.auth.opaque.TokenHasher;
import com.lunchcatch.member.entity.Member;
import com.lunchcatch.member.repository.MemberRepository;
import com.lunchcatch.member.service.MemberTokenService;
import jakarta.servlet.http.Cookie;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class MemberTokenRefreshIntegrationTest extends AbstractMemberIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired MemberTokenService tokens;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AccessTokenValidAfterRepository cutoff;
    @Autowired Clock clock;

    @MockitoSpyBean(name = "memberRefreshTokenBackupStore")
    RefreshTokenBackupStore backupStore;

    private Long memberId;

    @AfterEach
    void clean() {
        if (memberId != null) {
            members.deleteById(memberId);
        }
    }

    private String issue() {
        Member member = members.saveAndFlush(Member.create(UUID.randomUUID().toString(), "동시 재발급", null));
        memberId = member.getId();
        return tokens.issue(member).refreshToken();
    }

    /*
     * 재발급은 IP 마다 요청 제한(429)을 받는다. 테스트마다 다른 주소를 써서 앞 테스트의 요청이 뒤 테스트를 막지 않게 한다.
     * 문서용 주소 대역(198.51.100.0/24) 안에서 고른다.
     */
    private static final AtomicInteger NEXT_HOST = new AtomicInteger(86);
    private final String clientIp = "198.51.100." + NEXT_HOST.getAndIncrement();

    private MvcResult refresh(String token) throws Exception {
        return mvc.perform(post("/v1/auth/tokens:refresh")
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .cookie(new Cookie("refreshToken", token)))
                .andReturn();
    }

    @Test
    void 두_탭의_재발급이_겹쳐도_성공한_새_토큰과_DB_해시를_폐기하지_않는다() throws Exception {
        String oldToken = issue();
        CountDownLatch updatingDatabase = new CountDownLatch(1);
        CountDownLatch continueRotation = new CountDownLatch(1);
        doAnswer(invocation -> {
            updatingDatabase.countDown();
            assertThat(continueRotation.await(15, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(backupStore).rotateIfMatches(eq(memberId), any(), any(), any(), any());

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> refresh(oldToken));
            try {
                assertThat(updatingDatabase.await(15, TimeUnit.SECONDS)).isTrue();
                MvcResult duplicate = refresh(oldToken);
                status().isUnauthorized().match(duplicate);
                jsonPath("$.code").value("AUTH-003").match(duplicate);
                assertThat(duplicate.getResponse().getCookie("refreshToken")).isNull();
            } finally {
                continueRotation.countDown();
            }

            MvcResult success = first.get(20, TimeUnit.SECONDS);
            status().isOk().match(success);
            Cookie next = success.getResponse().getCookie("refreshToken");
            assertThat(next).isNotNull();
            String nextHash = TokenHasher.sha256(next.getValue());
            assertThat(members.findById(memberId).orElseThrow().getRefreshTokenHash()).isEqualTo(nextHash);
            assertThat(refreshTokens.findActiveHash(Role.MEMBER, memberId)).contains(nextHash);
            assertThat(cutoff.isValidAfter(Role.MEMBER, memberId, LocalDateTime.now(clock).minusSeconds(1))).isTrue();
            status().isOk().match(refresh(next.getValue()));
        }
    }

    @Test
    void 회전이_끝난_옛_토큰을_재사용하면_전체_폐기를_유지한다() throws Exception {
        String oldToken = issue();
        status().isOk().match(refresh(oldToken));

        MvcResult reused = refresh(oldToken);

        status().isUnauthorized().match(reused);
        jsonPath("$.code").value("AUTH-004").match(reused);
        assertThat(members.findById(memberId).orElseThrow().getRefreshTokenHash()).isNull();
        assertThat(refreshTokens.findActiveHash(Role.MEMBER, memberId)).isEmpty();
    }

    // 별도 트랜잭션이 커넥션을 얻지 못하면 TransactionException 으로 온다. 그때도 보상하고 명세대로 503 AUTH-002 다(#1320)
    @Test
    void DB_회전이_커넥션을_얻지_못해도_처리_중_표시를_복구하고_503_AUTH002_다() throws Exception {
        String oldToken = issue();
        doThrow(new CannotCreateTransactionException("pool exhausted")).doCallRealMethod()
                .when(backupStore).rotateIfMatches(eq(memberId), any(), any(), any(), any());

        MvcResult failed = refresh(oldToken);

        status().isServiceUnavailable().match(failed);
        jsonPath("$.code").value("AUTH-002").match(failed);
        assertThat(failed.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(members.findById(memberId).orElseThrow().getRefreshTokenHash())
                .isEqualTo(TokenHasher.sha256(oldToken));
        status().isOk().match(refresh(oldToken));
    }

    // 코드 버그는 503 으로 감추지 않고 500 이다. 보상은 그대로 해서 기존 토큰을 잃지 않는다(#1320)
    @Test
    void DB_회전이_SQL_오류로_실패하면_처리_중_표시를_복구하고_500_이다() throws Exception {
        String oldToken = issue();
        doThrow(new BadSqlGrammarException("rotate", "UPDATE", new SQLException("syntax"))).doCallRealMethod()
                .when(backupStore).rotateIfMatches(eq(memberId), any(), any(), any(), any());

        MvcResult failed = refresh(oldToken);

        status().isInternalServerError().match(failed);
        jsonPath("$.code").value("COMMON-001").match(failed);
        assertThat(members.findById(memberId).orElseThrow().getRefreshTokenHash())
                .isEqualTo(TokenHasher.sha256(oldToken));
        status().isOk().match(refresh(oldToken));
    }

    @Test
    void DB_회전에_실패하면_처리_중_표시를_복구해_기존_토큰으로_재시도할_수_있다() throws Exception {
        String oldToken = issue();
        doThrow(new QueryTimeoutException("db timeout")).doCallRealMethod()
                .when(backupStore).rotateIfMatches(eq(memberId), any(), any(), any(), any());

        MvcResult failed = refresh(oldToken);

        status().isServiceUnavailable().match(failed);
        jsonPath("$.code").value("AUTH-002").match(failed);
        assertThat(members.findById(memberId).orElseThrow().getRefreshTokenHash())
                .isEqualTo(TokenHasher.sha256(oldToken));
        status().isOk().match(refresh(oldToken));
    }
}
