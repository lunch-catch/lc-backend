package com.lunchcatch.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.Role;
import com.lunchcatch.auth.jwt.AccessTokenValidAfterRepository;
import com.lunchcatch.auth.jwt.JwtTokenProvider;
import com.lunchcatch.auth.opaque.RefreshTokenRepository;
import com.lunchcatch.member.contract.AgeGroup;
import com.lunchcatch.member.contract.Gender;
import com.lunchcatch.member.contract.MemberStatus;
import com.lunchcatch.member.entity.Member;
import com.lunchcatch.member.entity.MemberProfile;
import com.lunchcatch.member.oauth.KakaoIdentity;
import com.lunchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.lunchcatch.member.repository.MemberProfileRepository;
import com.lunchcatch.member.repository.MemberRepository;
import com.lunchcatch.member.repository.MemberWithdrawalHistoryRepository;
import com.lunchcatch.member.service.MemberTokenService;
import com.lunchcatch.member.service.MemberWithdrawalService;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

class MemberWithdrawalIntegrationTest extends AbstractMemberIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired MemberWithdrawalHistoryRepository history;
    @Autowired MemberProfileRepository profiles;
    @Autowired KakaoUnlinkFailureRepository unlinkQueue;
    @Autowired MemberTokenService tokens;
    @Autowired MemberWithdrawalService withdrawal;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AccessTokenValidAfterRepository cutoff;
    @Autowired JwtTokenProvider jwt;
    @Autowired TransactionTemplate transactions;
    @Autowired Clock clock;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 프로필_유무와_관계없이_탈퇴_API가_커밋되고_토큰과_쿠키를_폐기한다(boolean hasProfile) throws Exception {
        Member member = createMember(hasProfile);
        var pair = tokens.issue(member);
        when(exchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity(member.getProviderUserId(), "점심헌터", "https://image"));

        var response = mvc.perform(post("/v1/members/me:withdraw")
                        .cookie(new Cookie("accessToken", pair.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorizationCode\":\"code\",\"state\":\"state\"}"))
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertWithdrawn(member.getId(), pair);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(value -> assertThat(value).contains("accessToken=", "Max-Age=0"))
                .anySatisfy(value -> assertThat(value).contains("refreshToken=", "Max-Age=0"));
        assertThat(unlinkQueue.findByMember_Id(member.getId())).isEmpty();
        verify(unlink).unlink(member.getProviderUserId());
    }

    @Test
    void 프로필이_있는_회원의_웹훅도_탈퇴를_커밋하고_토큰을_폐기한다() {
        Member member = createMember(true);
        var pair = tokens.issue(member);

        withdrawal.withdrawByKakaoWebhook(member.getProviderUserId());

        assertWithdrawn(member.getId(), pair);
    }

    @Test
    void 카카오_연결_해제에_실패해도_프로필을_삭제하고_해제_대상을_남긴다() {
        Member member = createMember(true);
        var pair = tokens.issue(member);
        when(exchanger.exchange("code", "state"))
                .thenReturn(new KakaoIdentity(member.getProviderUserId(), "점심헌터", "https://image"));
        doThrow(new RuntimeException("network")).when(unlink).unlink(anyString());

        withdrawal.withdraw(member.getId(), "code", "state");

        assertWithdrawn(member.getId(), pair);
        assertThat(unlinkQueue.findByMember_Id(member.getId())).isPresent();
    }

    private Member createMember(boolean hasProfile) {
        return transactions.execute(status -> {
            Member member = members.save(Member.create("withdraw-" + UUID.randomUUID(), "점심헌터", "https://image"));
            if (hasProfile) {
                MemberProfile profile = MemberProfile.create(member);
                profile.completeOnboarding(Gender.MALE, AgeGroup.AGE_20S, LocalDateTime.now(clock));
                profiles.save(profile);
                member.completeOnboarding(true, true, LocalDateTime.now(clock));
            }
            return member;
        });
    }

    private void assertWithdrawn(Long memberId, MemberTokenService.TokenPair pair) {
        transactions.executeWithoutResult(status -> {
            Member member = members.findById(memberId).orElseThrow();
            assertThat(member.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
            assertThat(member.getProviderUserId()).isNull();
            assertThat(history.findByMemberId(memberId)).isPresent();
            assertThat(member.getWithdrawnAt()).isNotNull();
            assertThat(member.getNickname()).isEqualTo("탈퇴한 회원");
            assertThat(member.getProfileImageUrl()).isNull();
            assertThat(member.getProfile()).isNull();
            assertThat(member.isNotificationOptIn()).isFalse();
            assertThat(member.isLocationOptIn()).isFalse();
            assertThat(member.getRefreshTokenHash()).isNull();
            assertThat(member.getRefreshTokenExpiresAt()).isNull();
            assertThat(profiles.findByMember_Id(memberId)).isEmpty();
        });
        assertThat(refreshTokens.find(pair.refreshToken())).isEmpty();
        assertThat(refreshTokens.findActiveHash(Role.MEMBER, memberId)).isEmpty();
        assertThat(cutoff.isValidAfter(Role.MEMBER, memberId, jwt.getIssuedAt(pair.accessToken()))).isFalse();
    }
}
