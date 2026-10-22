package com.launchcatch.owner.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.launchcatch.auth.Role;
import com.launchcatch.owner.dto.OwnerSignupRequest;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.entity.OwnerStatus;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OwnerSignupServiceTest {
    private final OwnerRepository repository = mock(OwnerRepository.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private OwnerSignupService service;
    private final OwnerSignupRequest request = new OwnerSignupRequest("owner@example.com", "password12");

    @BeforeEach
    void setUp() {
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        service = new OwnerSignupService(repository, encoder, manager);
    }

    @Test
    void 가입하면_해시와_서버가_정한_초기_상태를_저장한다() {
        AtomicReference<Owner> stored = new AtomicReference<>();
        when(repository.saveAndFlush(any(Owner.class))).thenAnswer(invocation -> {
            Owner owner = invocation.getArgument(0);
            stored.set(owner);
            return owner;
        });

        var response = service.signup(request);

        assertThat(response.email()).isEqualTo(request.email());
        assertThat(response.role()).isEqualTo(Role.OWNER);
        assertThat(response.status()).isEqualTo(OwnerStatus.ONBOARDING);
        assertThat(encoder.matches(request.password(), stored.get().getPasswordHash())).isTrue();
        assertThat(stored.get().getPasswordHash()).isNotEqualTo(request.password());
        assertThat(stored.get().isTutorialViewed()).isFalse();
        assertThat(stored.get().getRefreshTokenHash()).isNull();
        assertThat(stored.get().getRefreshTokenExpiresAt()).isNull();
        assertThat(stored.get().getLastLoginAt()).isNull();
    }

    @Test
    void 기존_이메일이면_가입을_거절한다() {
        when(repository.existsByEmail(request.email())).thenReturn(true);
        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOfSatisfying(OwnerException.class,
                        e -> assertThat(e.getErrorCode().getCode()).isEqualTo("OWNER-001"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void 동시_가입의_UNIQUE_위반도_이메일_중복으로_처리한다() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_owner_email"));
        assertThatThrownBy(() -> service.signup(request)).isInstanceOf(OwnerException.class);
        verify(manager).rollback(any());
    }

    @Test
    void 다른_DB_오류는_이메일_중복으로_오인하지_않는다() {
        var failure = new DataIntegrityViolationException("other_constraint");
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.signup(request)).isSameAs(failure);
        verify(manager).rollback(any());
    }
}