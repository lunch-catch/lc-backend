package com.launchcatch.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import com.launchcatch.member.contract.MemberStatsQueryService;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.entity.MemberWithdrawalHistory;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberWithdrawalHistoryRepository;
import com.launchcatch.member.service.MemberTokenService;
import com.launchcatch.member.service.KakaoUnlinkRetryService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

class MemberRejoinIntegrationTest extends AbstractMemberIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired MemberProfileRepository profiles;
    @Autowired TransactionTemplate transactions;
    @Autowired MemberWithdrawalHistoryRepository history;
    @Autowired KakaoUnlinkFailureRepository unlinkQueue;
    @Autowired MemberTokenService tokens;
    @Autowired KakaoUnlinkRetryService unlinkRetry;
    @Autowired MemberStatsQueryService stats;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    private static final AtomicInteger REQUEST_IP = new AtomicInteger();

    @Test
    void 다음_날_재가입은_새_회원과_새_가입시각을_만들고_옛_탈퇴를_유지한다() throws Exception {
        Member previous = withdrawYesterday();
        String provider = history.findByMemberId(previous.getId()).orElseThrow().getProviderUserId();
        var before = stats.dailyStats(LocalDate.now(clock));

        MvcResult response = login(provider);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        Number freshId = JsonPath.read(response.getResponse().getContentAsString(), "$.data.memberId");
        assertThat(freshId.longValue()).isNotEqualTo(previous.getId());
        assertThat(JsonPath.<Boolean>read(response.getResponse().getContentAsString(), "$.data.newMember")).isTrue();
        assertThat(JsonPath.<Boolean>read(response.getResponse().getContentAsString(), "$.data.onboardingCompleted")).isFalse();
        Member fresh = members.findById(freshId.longValue()).orElseThrow();
        assertThat(fresh.getCreatedAt().toLocalDate()).isEqualTo(LocalDate.now(clock));
        assertThat(fresh.isLocationOptIn()).isFalse();
        assertThat(fresh.isNotificationOptIn()).isFalse();
        assertThat(profiles.findByMember_Id(fresh.getId())).isEmpty();
        assertThat(profiles.findByMember_Id(previous.getId())).isEmpty();
        assertThat(members.findById(previous.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(history.findByMemberId(previous.getId())).isPresent();
        var after = stats.dailyStats(LocalDate.now(clock));
        assertThat(after.newMemberCount()).isEqualTo(before.newMemberCount() + 1);
        assertThat(after.cumulativeMemberCount()).isEqualTo(before.cumulativeMemberCount() + 1);
        assertThat(after.withdrawnCount()).isEqualTo(before.withdrawnCount());
    }

    @Test
    void 당일에는_HTTP_재가입을_거부하고_새_회원_행을_만들지_않는다() throws Exception {
        Member previous = createMember();
        String provider = previous.getProviderUserId();
        withdraw(previous);

        MvcResult result = login(provider);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code")).isEqualTo("MEMBER-004");
        assertThat(members.findByProviderUserId(provider)).isEmpty();
    }

    @Test
    void 동시에_재가입해도_새_활성_계정은_하나이고_옛_계정은_탈퇴로_남는다() throws Exception {
        Member previous = withdrawYesterday();
        String provider = history.findByMemberId(previous.getId()).orElseThrow().getProviderUserId();
        stubLogin(provider);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> requests = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                requests.add(executor.submit(() -> {
                    start.await();
                    return loginRequest(provider);
                }));
            }
            start.countDown();
            for (Future<MvcResult> request : requests) {
                assertThat(request.get(40, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            }
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE provider_user_id = ?", Long.class, provider))
                .isEqualTo(1L);
        assertThat(members.findByProviderUserId(provider).orElseThrow().getId()).isNotEqualTo(previous.getId());
        assertThat(history.findByMemberId(previous.getId())).isPresent();
    }

    @Test
    void 이력_저장이_실패하면_회원과_프로필과_해제작업을_함께_롤백한다() throws Exception {
        Member member = createMember();
        String provider = member.getProviderUserId();
        history.saveAndFlush(MemberWithdrawalHistory.create(member, LocalDateTime.now(clock).minusDays(1)));
        var pair = tokens.issue(member);
        when(exchanger.exchange("withdraw-" + provider, "state"))
                .thenReturn(new KakaoIdentity(provider, "닉네임", null));

        mvc.perform(post("/v1/members/me:withdraw")
                        .cookie(new Cookie("accessToken", pair.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"withdraw-" + provider + "\",\"state\":\"state\"}"))
                .andExpect(status().isInternalServerError());

        Member persisted = members.findById(member.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(persisted.getProviderUserId()).isEqualTo(provider);
        assertThat(profiles.findByMember_Id(member.getId())).isPresent();
        assertThat(persisted.isLocationOptIn()).isTrue();
        assertThat(unlinkQueue.findByMember_Id(member.getId())).isEmpty();
    }

    @Test
    void 중복_웹훅은_이력을_늘리지_않고_재가입_뒤_지연_웹훅은_새_계정을_유지한다() throws Exception {
        Member previous = withdrawYesterday();
        String provider = history.findByMemberId(previous.getId()).orElseThrow().getProviderUserId();
        webhook(provider);
        webhook(provider);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_withdrawal_history WHERE member_id = ?",
                Long.class, previous.getId())).isEqualTo(1L);
        login(provider);

        webhook(provider);

        assertThat(members.findByProviderUserId(provider).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    void 최초_해제_호출_중에는_배치가_중복_호출하지_않고_재가입도_보류한다() throws Exception {
        Member previous = createMember();
        String provider = previous.getProviderUserId();
        stubLogin(provider);
        org.mockito.Mockito.doAnswer(invocation -> {
            LocalDateTime yesterday = LocalDate.now(clock).minusDays(1).atTime(13, 0);
            jdbc.update("UPDATE member_withdrawal_history SET withdrawn_at = ? WHERE member_id = ?", yesterday, previous.getId());
            assertThat(unlinkQueue.findByMember_Id(previous.getId()).orElseThrow().isProcessing()).isTrue();
            unlinkRetry.retryPending();
            MvcResult response = loginRequest(provider);
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.getResponse().getContentAsString(), "$.code")).isEqualTo("MEMBER-005");
            webhook(provider);
            assertThat(unlinkQueue.findByMember_Id(previous.getId())).isPresent();
            return null;
        }).when(unlink).unlink(provider);

        withdraw(previous);

        org.mockito.Mockito.verify(unlink).unlink(provider);
        assertThat(unlinkQueue.findByMember_Id(previous.getId())).isEmpty();
        assertThat(loginRequest(provider).getResponse().getStatus()).isEqualTo(200);
    }

    private Member createMember() {
        return transactions.execute(status -> {
            Member member = members.saveAndFlush(Member.create("rejoin-" + UUID.randomUUID(), "이전닉네임", null));
            MemberProfile profile = MemberProfile.create(member);
            profile.completeOnboarding(Gender.FEMALE, AgeGroup.AGE_20S, LocalDateTime.now(clock));
            profiles.save(profile);
            member.completeOnboarding(true, true, LocalDateTime.now(clock));
            return member;
        });
    }

    private Member withdrawYesterday() throws Exception {
        Member previous = createMember();
        withdraw(previous);
        LocalDateTime yesterday = LocalDate.now(clock).minusDays(1).atTime(13, 0);
        jdbc.update("UPDATE member SET withdrawn_at = ?, created_at = ? WHERE member_id = ?",
                yesterday, yesterday.minusDays(1), previous.getId());
        jdbc.update("UPDATE member_withdrawal_history SET withdrawn_at = ? WHERE member_id = ?", yesterday, previous.getId());
        return previous;
    }

    private void withdraw(Member member) throws Exception {
        String provider = member.getProviderUserId();
        var pair = tokens.issue(member);
        when(exchanger.exchange("withdraw-" + provider, "state"))
                .thenReturn(new KakaoIdentity(provider, "닉네임", null));
        mvc.perform(post("/v1/members/me:withdraw")
                        .cookie(new Cookie("accessToken", pair.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"withdraw-" + provider + "\",\"state\":\"state\"}"))
                .andExpect(status().isNoContent());
    }

    private MvcResult login(String provider) throws Exception {
        stubLogin(provider);
        return loginRequest(provider);
    }

    private void stubLogin(String provider) {
        when(exchanger.exchange("login-" + provider, "state"))
                .thenReturn(new KakaoIdentity(provider, "새닉네임", null));
    }

    private MvcResult loginRequest(String provider) throws Exception {
        return mvc.perform(post("/v1/auth/tokens")
                        .with(request -> { request.setRemoteAddr("198.51.100." + REQUEST_IP.incrementAndGet()); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"login-" + provider + "\",\"state\":\"state\"}"))
                .andReturn();
    }

    private void webhook(String provider) throws Exception {
        mvc.perform(get("/webhook/kakao/unlink")
                        .header("Authorization", "KakaoAK test-kakao-admin-key")
                        .param("app_id", "test-kakao-app-id").param("user_id", provider))
                .andExpect(status().isOk());
    }
}
