package com.lunchcatch.billing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.owner.contract.OwnerAccountStatus;
import com.lunchcatch.owner.contract.OwnerStatusQuery;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(MockitoExtension.class)
class OwnerActiveInterceptorTest {

    private static final long OWNER_ID = 7L;

    @Mock
    private OwnerStatusQuery ownerStatusQuery;

    private OwnerActiveInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new OwnerActiveInterceptor(ownerStatusQuery);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ACTIVE_점주는_통과한다() {
        authenticateAsOwner();
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ACTIVE));

        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OwnerAccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void ACTIVE가_아닌_점주는_막는다(OwnerAccountStatus status) {
        authenticateAsOwner();
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(status));

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.OWNER_NOT_ACTIVE));
    }

    @Test
    void 없는_점주는_막는다() {
        authenticateAsOwner();
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.OWNER_NOT_ACTIVE));
    }

    @Test
    void 인증_주체를_알_수_없으면_막는다() {
        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.OWNER_NOT_ACTIVE));
    }

    // 보안 체인이 정한 점주 경로 목록에 인터셉터를 걸었을 때 실제 경로가 걸리는지 MVC 매칭으로 본다.
    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/owner/payments",
            "/v1/owner/payments/101",
            "/v1/owner/point-policy",
            "/v1/owner/points/balance",
            "/v1/owner/points/ledger",
            "/v1/owner/refund-requests",
            "/v1/owner/refund-requests/preview"
    })
    void 점주_결제_포인트_환불_경로는_상태를_검사한다(String path) {
        authenticateAsOwner();
        when(ownerStatusQuery.findCurrentStatus(OWNER_ID)).thenReturn(Optional.of(OwnerAccountStatus.ONBOARDING));

        assertThatThrownBy(() -> mockMvc().perform(get(path)))
                .hasCauseInstanceOf(BillingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/webhook/payments", "/v1/admin/payments"})
    void 웹훅과_관리자_경로는_상태를_검사하지_않는다(String path) {
        authenticateAsOwner();

        assertThatCode(() -> mockMvc().perform(post(path)).andExpect(status().isOk()))
                .doesNotThrowAnyException();
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new StubController())
                .addMappedInterceptors(BillingSecurityConfig.OWNER_PATHS, interceptor)
                .build();
    }

    private void authenticateAsOwner() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserDetails(OWNER_ID, Role.OWNER), null, List.of()));
    }

    @RestController
    static class StubController {

        @GetMapping({
                "/v1/owner/payments", "/v1/owner/payments/{id}", "/v1/owner/point-policy",
                "/v1/owner/points/balance", "/v1/owner/points/ledger",
                "/v1/owner/refund-requests", "/v1/owner/refund-requests/preview"})
        String owner() {
            return "ok";
        }

        @PostMapping({"/webhook/payments", "/v1/admin/payments"})
        String other() {
            return "ok";
        }
    }
}
