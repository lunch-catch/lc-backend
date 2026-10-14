package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.launchcatch.IntegrationTestSupport;
import com.launchcatch.admin.dto.AdminRegistrationRequest;
import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.admin.service.AdminLogoutService;
import com.launchcatch.admin.service.AdminRegistrationService;
import com.launchcatch.auth.Role;
import com.launchcatch.auth.exception.AuthErrorCode;
import com.launchcatch.auth.exception.AuthException;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = "jwt.secret=test-only-secret-not-used-anywhere-else-0123456789abcdef")
class AdminRegistrationLogoutConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired private AdminRepository admins;
    @Autowired private AdminRegistrationService registration;
    @Autowired private AdminLogoutService logout;
    @MockitoSpyBean private PasswordEncoder encoder;
    @MockitoSpyBean private com.launchcatch.ops.contract.AuditLogWriter audit;
    private Long issuerId;
    private LocalDateTime issuedAt;

    @BeforeEach
    void setUp() {
        issuedAt = LocalDateTime.now().minusSeconds(1);
        var issuer = Admin.register("concurrent.issuer", "password-hash", "최고관리자", Role.SUPER_ADMIN);
        issuer.issueRefreshToken("a".repeat(64), issuedAt.plusDays(1));
        issuerId = admins.saveAndFlush(issuer).getId();
    }

    @AfterEach
    void tearDown() {
        admins.findByLoginId("concurrent.created").ifPresent(admin -> admins.deleteById(admin.getId()));
        admins.deleteById(issuerId);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 1L)
    void 사전검증_후_로그아웃이_커밋되면_계정과_성공감사를_생성하지_않는다(Long version) throws Exception {
        var hashing = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            hashing.countDown();
            if (!resume.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("password hashing wait timed out");
            }
            return call.callRealMethod();
        }).when(encoder).encode(eq("Initial123!"));

        try (var workers = Executors.newSingleThreadExecutor()) {
            var result = workers.submit(() -> assertThrows(AuthException.class,
                    () -> registration.register(issuerId, Role.SUPER_ADMIN, issuedAt, version,
                            new AdminRegistrationRequest("concurrent.created", "Initial123!", "관리자", "ADMIN"))));
            try {
                assertThat(hashing.await(30, TimeUnit.SECONDS)).isTrue();
                logout.logout(issuerId, Role.SUPER_ADMIN);
            } finally {
                resume.countDown();
            }
            assertThat(result.get(30, TimeUnit.SECONDS).getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_REQUIRED);
        }
        assertThat(admins.findByLoginId("concurrent.created")).isEmpty();
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.never()).write(eq(issuerId),
                eq("ADMIN_ACCOUNT_CREATE"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

}

