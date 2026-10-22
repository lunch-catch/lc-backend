package com.launchcatch.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.auth.opaque.OpaqueRefreshTokenLifecycle;
import com.launchcatch.member.client.KakaoLogoutClient;
import com.launchcatch.member.client.KakaoUnlinkClient;
import com.launchcatch.member.contract.MemberStatus;
import com.launchcatch.member.dto.MemberLocationRequest;
import com.launchcatch.member.dto.MemberOnboardingRequest;
import com.launchcatch.member.dto.MemberUpdateRequest;
import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.entity.MemberProfile;
import com.launchcatch.member.entity.KakaoUnlinkFailure;
import com.launchcatch.member.exception.MemberException;
import com.launchcatch.member.oauth.KakaoIdTokenExchanger;
import com.launchcatch.member.oauth.KakaoIdentity;
import com.launchcatch.member.repository.KakaoUnlinkFailureRepository;
import com.launchcatch.member.repository.MemberProfileRepository;
import com.launchcatch.member.repository.MemberRepository;
import com.launchcatch.member.repository.MemberWithdrawalHistoryRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class MemberBusinessLoggingTest {

    private final MemberRepository members = mock(MemberRepository.class);
    private final MemberProfileRepository profiles = mock(MemberProfileRepository.class);
    private final Clock clock = Clock.systemUTC();

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void 업무_도중의_DB_실패는_원문_없이_한_번만_기록한다(Mutation mutation) {
        RuntimeException failure = new DataAccessResourceFailureException("요청 개인정보 원문");
        LoggedOperation operation = operation(mutation, failure);
        try (Capture capture = new Capture(operation.logger())) {
            assertThatThrownBy(operation.call()::run).isSameAs(failure);

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo("event=" + operation.event()
                        + "_FAILED memberId=" + (mutation == Mutation.TOKEN_REFRESH ? "null" : "42")
                        + " reason=SYSTEM_ERROR exceptionType=DataAccessResourceFailureException");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void 커밋_실패는_성공_대신_업무_실패를_한_번만_기록한다(Mutation mutation) {
        LoggedOperation operation = operation(mutation, null);
        TestTransactionManager manager = new TestTransactionManager();
        manager.failCommit = true;
        try (Capture capture = new Capture(operation.logger())) {
            assertThatThrownBy(() -> new TransactionTemplate(manager)
                    .executeWithoutResult(status -> operation.call().run()))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo("event=" + operation.event()
                        + "_FAILED memberId=42 reason=TRANSACTION_COMPLETION_FAILED");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 탈퇴의_DB_또는_커밋_실패에는_회원_식별자를_기록한다(boolean failCommit) {
        MemberWithdrawalService service = withdrawalService(failCommit);
        Class<? extends RuntimeException> expectedType = failCommit
                ? IllegalStateException.class : DataAccessResourceFailureException.class;
        try (Capture capture = new Capture(MemberWithdrawalService.class)) {
            var failure = assertThatThrownBy(() -> service.withdraw(42L, "인가코드원문", "상태검증원문"))
                    .isInstanceOf(expectedType);
            if (failCommit) {
                failure.hasMessage("커밋 실패");
            }

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo("event=MEMBER_WITHDRAWAL_FAILED memberId=42 reason=SYSTEM_ERROR exceptionType="
                        + expectedType.getSimpleName());
                assertThat(event.getFormattedMessage()).doesNotContain("원문", "SUCCESS");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    private MemberWithdrawalService withdrawalService(boolean failCommit) {
        Member member = member();
        when(members.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        if (!failCommit) {
            when(members.findByIdForUpdate(42L)).thenThrow(new DataAccessResourceFailureException("요청 개인정보 원문"));
        }
        KakaoIdTokenExchanger exchanger = mock(KakaoIdTokenExchanger.class);
        when(exchanger.exchange("인가코드원문", "상태검증원문"))
                .thenReturn(new KakaoIdentity(member.getProviderUserId(), "닉네임원문", null));
        TestTransactionManager manager = new TestTransactionManager();
        manager.failCommit = failCommit;
        KakaoUnlinkFailureRepository unlinkFailures = mock(KakaoUnlinkFailureRepository.class);
        if (failCommit) {
            when(unlinkFailures.save(any(KakaoUnlinkFailure.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
        }
        return new MemberWithdrawalService(exchanger, members, profiles,
                unlinkFailures, mock(MemberWithdrawalHistoryRepository.class), mock(MemberTokenService.class), mock(KakaoUnlinkClient.class),
                new TransactionTemplate(manager), clock);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 조회_DB_실패에도_회원_식별자를_기록한다(boolean location) {
        RuntimeException failure = new DataAccessResourceFailureException("요청 개인정보 원문");
        when(members.findById(42L)).thenThrow(failure);
        when(profiles.findByMember_Id(42L)).thenThrow(failure);
        Class<?> logger = location ? MemberLocationService.class : MemberProfileService.class;
        Runnable call = location ? () -> new MemberLocationService(members, profiles).get(42L)
                : () -> new MemberProfileService(members, profiles, clock).getMyProfile(42L);
        try (Capture capture = new Capture(logger)) {
            assertThatThrownBy(call::run).isSameAs(failure);

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo("event=MEMBER_" + (location ? "LOCATION" : "PROFILE")
                        + "_READ_FAILED memberId=42 reason=SYSTEM_ERROR exceptionType=DataAccessResourceFailureException");
            });
        }
    }

    private LoggedOperation operation(Mutation mutation, RuntimeException failure) {
        Member member = member();
        when(members.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        when(members.findById(42L)).thenReturn(Optional.of(member));
        when(profiles.findByMember_Id(42L)).thenReturn(Optional.of(MemberProfile.create(member)));
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        OpaqueRefreshTokenLifecycle lifecycle = mock(OpaqueRefreshTokenLifecycle.class);
        when(lifecycle.reissue(any(), any(), any(), any(), any(), any()))
                .thenReturn(new OpaqueRefreshTokenLifecycle.ReissueResult(42L, "갱신토큰원문"));
        if (failure != null) {
            switch (mutation) {
                case ONBOARDING, PROFILE_UPDATE, LOCATION_SAVE -> when(members.findByIdForUpdate(42L)).thenThrow(failure);
                case LOCATION_DELETE -> when(profiles.findByMember_Id(42L)).thenThrow(failure);
                case TOKEN_REFRESH -> when(lifecycle.reissue(any(), any(), any(), any(), any(), any())).thenThrow(failure);
                case LOGOUT -> when(members.findById(42L)).thenThrow(failure);
            }
        }
        MemberProfileService profileService = new MemberProfileService(members, profiles, clock);
        MemberLocationService locationService = new MemberLocationService(members, profiles);
        MemberTokenService tokens = new MemberTokenService(jwt, lifecycle, members, mock(KakaoLogoutClient.class), clock);
        return switch (mutation) {
            case ONBOARDING -> new LoggedOperation(MemberProfileService.class, "MEMBER_ONBOARDING",
                    () -> profileService.completeOnboarding(42L, onboarding()));
            case PROFILE_UPDATE -> new LoggedOperation(MemberProfileService.class, "MEMBER_PROFILE_UPDATE",
                    () -> profileService.updateMyProfile(42L, new MemberUpdateRequest("닉네임원문", true, false)));
            case LOCATION_SAVE -> new LoggedOperation(MemberLocationService.class, "MEMBER_LOCATION_SAVE",
                    () -> locationService.save(42L, new MemberLocationRequest("집", "주소원문", BigDecimal.ONE, BigDecimal.ONE)));
            case LOCATION_DELETE -> new LoggedOperation(MemberLocationService.class, "MEMBER_LOCATION_DELETE",
                    () -> locationService.delete(42L));
            case TOKEN_REFRESH -> new LoggedOperation(MemberTokenService.class, "MEMBER_TOKEN_REFRESH",
                    () -> tokens.reissue("갱신토큰원문"));
            case LOGOUT -> new LoggedOperation(MemberTokenService.class, "MEMBER_LOGOUT", () -> tokens.logout(42L));
        };
    }

    private enum Mutation {
        ONBOARDING, PROFILE_UPDATE, LOCATION_SAVE, LOCATION_DELETE, TOKEN_REFRESH, LOGOUT
    }

    private record LoggedOperation(Class<?> logger, String event, Runnable call) {
    }

    @Test
    void 온보딩_성공은_커밋_후_식별자만_기록한다() {
        MemberProfileService service = onboardingService();
        try (Capture capture = new Capture(MemberProfileService.class)) {
            new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
                service.completeOnboarding(42L, onboarding());
                assertThat(capture.appender.list).isEmpty();
            });

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).isEqualTo("event=MEMBER_ONBOARDING_SUCCESS memberId=42");
            });
        }
    }

    @Test
    void 롤백된_온보딩은_성공으로_기록하지_않는다() {
        MemberProfileService service = onboardingService();
        try (Capture capture = new Capture(MemberProfileService.class)) {
            new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
                service.completeOnboarding(42L, onboarding());
                status.setRollbackOnly();
            });

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).isEqualTo("event=MEMBER_ONBOARDING_FAILED memberId=42 reason=TRANSACTION_COMPLETION_FAILED");
            });
        }
    }

    @Test
    void 중복_온보딩은_성공_대신_실패_사유를_기록한다() {
        Member member = member();
        MemberProfile profile = MemberProfile.create(member);
        profile.completeOnboarding(Gender.FEMALE, AgeGroup.AGE_20S, LocalDateTime.now(clock));
        when(members.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        MemberProfileService service = new MemberProfileService(members, profiles, clock);
        try (Capture capture = new Capture(MemberProfileService.class)) {
            assertThatThrownBy(() -> service.completeOnboarding(42L, onboarding())).isInstanceOf(MemberException.class);

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage()).isEqualTo("event=MEMBER_ONBOARDING_FAILED memberId=42 reason=ONBOARDING_ALREADY_COMPLETED");
            });
        }
    }

    @Test
    void 위치_저장_로그에_주소와_좌표를_남기지_않는다() {
        Member member = member();
        when(members.findByIdForUpdate(42L)).thenReturn(Optional.of(member));
        when(profiles.findByMember_Id(42L)).thenReturn(Optional.of(MemberProfile.create(member)));
        MemberLocationService service = new MemberLocationService(members, profiles);
        try (Capture capture = new Capture(MemberLocationService.class)) {
            service.save(42L, new MemberLocationRequest("집", "서울특별시 중구 세종대로 110",
                    new BigDecimal("37.5665123"), new BigDecimal("126.9779692")));

            assertThat(capture.appender.list).singleElement().satisfies(event ->
                    assertThat(event.getFormattedMessage()).isEqualTo("event=MEMBER_LOCATION_SAVE_SUCCESS memberId=42"));
        }
    }

    @Test
    void 로그인_인증_실패에는_코드와_상태_검증값을_남기지_않는다() {
        KakaoIdTokenExchanger exchanger = mock(KakaoIdTokenExchanger.class);
        when(exchanger.exchange("인가코드원문", "상태검증원문"))
                .thenThrow(new AuthException(AuthErrorCode.KAKAO_AUTHENTICATION_FAILED));
        MemberLoginService service = new MemberLoginService(exchanger, members, profiles,
                mock(KakaoUnlinkFailureRepository.class), mock(MemberWithdrawalHistoryRepository.class), mock(MemberTokenService.class), mock(TransactionTemplate.class), clock);
        try (Capture capture = new Capture(MemberLoginService.class)) {
            assertThatThrownBy(() -> service.login("인가코드원문", "상태검증원문")).isInstanceOf(AuthException.class);

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("event=MEMBER_LOGIN_FAILED memberId=null errorCode=AUTH-007");
                assertThat(event.getThrowableProxy()).isNull();
            });
        }
    }

    @Test
    void 예상된_토큰_만료는_운영_경고를_늘리지_않는다() {
        OpaqueRefreshTokenLifecycle lifecycle = mock(OpaqueRefreshTokenLifecycle.class);
        when(lifecycle.reissue(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AuthException(AuthErrorCode.REFRESH_TOKEN_INVALID));
        MemberTokenService service = new MemberTokenService(mock(JwtTokenProvider.class), lifecycle,
                members, mock(KakaoLogoutClient.class), clock);
        try (Capture capture = new Capture(MemberTokenService.class)) {
            assertThatThrownBy(() -> service.reissue("갱신토큰원문")).isInstanceOf(AuthException.class);

            assertThat(capture.appender.list).isEmpty();
        }
    }

    @Test
    void 토큰_재발급_저장소_장애의_DB_원인을_기록한다() {
        OpaqueRefreshTokenLifecycle lifecycle = mock(OpaqueRefreshTokenLifecycle.class);
        AuthException failure = storeFailure();
        when(lifecycle.reissue(any(), any(), any(), any(), any(), any())).thenThrow(failure);
        MemberTokenService service = new MemberTokenService(mock(JwtTokenProvider.class), lifecycle,
                members, mock(KakaoLogoutClient.class), clock);
        try (Capture capture = new Capture(MemberTokenService.class)) {
            assertThatThrownBy(() -> service.reissue("갱신토큰원문")).isSameAs(failure);

            assertStoreFailure(capture, "MEMBER_TOKEN_REFRESH_FAILED");
        }
    }

    @Test
    void 로그아웃_저장소_장애의_DB_원인을_기록한다() {
        OpaqueRefreshTokenLifecycle lifecycle = mock(OpaqueRefreshTokenLifecycle.class);
        AuthException failure = storeFailure();
        when(members.findById(42L)).thenReturn(Optional.empty());
        doThrow(failure).when(lifecycle).revoke(any(), any(), any(), any());
        MemberTokenService service = new MemberTokenService(mock(JwtTokenProvider.class), lifecycle,
                members, mock(KakaoLogoutClient.class), clock);
        try (Capture capture = new Capture(MemberTokenService.class)) {
            assertThatThrownBy(() -> service.logout(42L)).isSameAs(failure);

            assertStoreFailure(capture, "MEMBER_LOGOUT_FAILED");
        }
    }

    @Test
    void 로그인_토큰_발급_실패의_DB_원인을_기록한다() {
        Member member = member();
        KakaoIdTokenExchanger exchanger = mock(KakaoIdTokenExchanger.class);
        when(exchanger.exchange("인가코드원문", "상태검증원문"))
                .thenReturn(new KakaoIdentity(member.getProviderUserId(), "닉네임원문", null));
        when(members.findByProviderUserIdForUpdate(member.getProviderUserId())).thenReturn(Optional.of(member));
        MemberTokenService tokens = mock(MemberTokenService.class);
        AuthException failure = storeFailure();
        when(tokens.issue(member)).thenThrow(failure);
        MemberLoginService service = new MemberLoginService(exchanger, members, profiles,
                mock(KakaoUnlinkFailureRepository.class), mock(MemberWithdrawalHistoryRepository.class), tokens, new TransactionTemplate(new TestTransactionManager()), clock);
        try (Capture capture = new Capture(MemberLoginService.class)) {
            assertThatThrownBy(() -> service.login("인가코드원문", "상태검증원문")).isSameAs(failure);

            assertStoreFailure(capture, "MEMBER_LOGIN_FAILED");
        }
    }

    @Test
    void 카카오_로그아웃_실패에는_상태와_원인을_남기고_회원_로그아웃은_유지한다() {
        when(members.findById(42L)).thenReturn(Optional.of(member()));
        KakaoLogoutClient client = mock(KakaoLogoutClient.class);
        doThrow(org.springframework.web.reactive.function.client.WebClientResponseException.create(
                503, "응답원문", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null)).when(client).logout(any());
        MemberTokenService service = new MemberTokenService(mock(JwtTokenProvider.class), mock(OpaqueRefreshTokenLifecycle.class),
                members, client, clock);
        try (Capture capture = new Capture(MemberTokenService.class)) {
            new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> service.logout(42L));

            assertThat(capture.appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message).contains("MEMBER_LOGOUT_SUCCESS memberId=42"))
                    .anySatisfy(message -> assertThat(message).contains("KAKAO_LOGOUT_AFTER_COMMIT_FAILED memberId=42", "httpStatus=503", "ServiceUnavailable")
                            .doesNotContain("원문"));
        }
    }

    private AuthException storeFailure() {
        return new AuthException(AuthErrorCode.REFRESH_TOKEN_STORE_UNAVAILABLE,
                new DataAccessResourceFailureException("개인정보원문", new SQLException("토큰원문", "08001", 1045)));
    }

    private void assertStoreFailure(Capture capture, String event) {
        assertThat(capture.appender.list).singleElement().satisfies(log -> {
            assertThat(log.getLevel()).isEqualTo(Level.ERROR);
            assertThat(log.getFormattedMessage()).contains("event=" + event, "errorCode=AUTH-002",
                    "DataAccessResourceFailureException", "SQLException", "sqlState=08001", "sqlErrorCode=1045")
                    .doesNotContain("원문");
            assertThat(log.getThrowableProxy()).isNull();
        });
    }

    @ParameterizedTest
    @EnumSource(value = MemberStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void 정지_회원의_로그인과_재가입_거부에도_회원번호를_한_번_기록한다(MemberStatus status) {
        Member member = deniedMember(status);
        KakaoIdTokenExchanger exchanger = mock(KakaoIdTokenExchanger.class);
        when(exchanger.exchange("인가코드원문", "상태검증원문"))
                .thenReturn(new KakaoIdentity(member.getProviderUserId(), "닉네임원문", null));
        when(members.findByProviderUserIdForUpdate(member.getProviderUserId())).thenReturn(Optional.of(member));
        MemberLoginService service = new MemberLoginService(exchanger, members, profiles,
                mock(KakaoUnlinkFailureRepository.class), mock(MemberWithdrawalHistoryRepository.class), mock(MemberTokenService.class),
                new TransactionTemplate(new TestTransactionManager()), clock);
        try (Capture capture = new Capture(MemberLoginService.class)) {
            assertThatThrownBy(() -> service.login("인가코드원문", "상태검증원문")).isInstanceOf(MemberException.class);

            assertThat(capture.appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("event=MEMBER_LOGIN_FAILED memberId=42 errorCode=MEMBER-003");
            });
        }
    }

    private Member deniedMember(MemberStatus status) {
        Member member = member();
        ReflectionTestUtils.setField(member, "status", status);
        ReflectionTestUtils.setField(member, "suspendedAt", LocalDateTime.now(clock));
        return member;
    }

    private MemberProfileService onboardingService() {
        when(members.findByIdForUpdate(42L)).thenReturn(Optional.of(member()));
        when(profiles.save(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return new MemberProfileService(members, profiles, clock);
    }

    private MemberOnboardingRequest onboarding() {
        return new MemberOnboardingRequest(Gender.FEMALE, AgeGroup.AGE_20S, true, false);
    }

    private Member member() {
        Member member = Member.create("카카오회원번호원문", "닉네임원문", null);
        ReflectionTestUtils.setField(member, "id", 42L);
        return member;
    }

    private static final class Capture implements AutoCloseable {
        private final Logger logger;
        private final Level originalLevel;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private Capture(Class<?> target) {
            logger = (Logger) LoggerFactory.getLogger(target);
            originalLevel = logger.getLevel();
            logger.setLevel(Level.INFO);
            appender.start();
            logger.addAppender(appender);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private boolean failCommit;
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new IllegalStateException("커밋 실패");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
