package com.launchcatch.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.launchcatch.auth.opaque.RefreshTokenBackupStore;
import com.launchcatch.auth.opaque.RefreshTokenRepository;
import com.launchcatch.auth.opaque.TokenHasher;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.service.MemberTokenService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    private MvcResult refresh(String token) throws Exception {
        return mvc.perform(post("/v1/auth/tokens:refresh")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.86");
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
