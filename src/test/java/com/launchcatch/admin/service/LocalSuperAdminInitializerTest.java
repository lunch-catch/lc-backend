package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.entity.AdminStatus;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.auth.Role;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class LocalSuperAdminInitializerTest {

    private static final String PASSWORD = "local-superadmin-1234";

    private final AdminRepository repository = mock(AdminRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final LocalSuperAdminInitializer initializer =
            new LocalSuperAdminInitializer(repository, encoder, PASSWORD);

    @Test
    void 최고관리자가_없으면_만든다() {
        when(repository.findByLoginId(LocalSuperAdminInitializer.LOGIN_ID)).thenReturn(Optional.empty());

        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<Admin> saved = ArgumentCaptor.forClass(Admin.class);
        verify(repository).save(saved.capture());
        Admin admin = saved.getValue();
        assertThat(admin.getLoginId()).isEqualTo("superadmin");
        assertThat(admin.getName()).isEqualTo("최고관리자");
        assertThat(admin.getRole()).isEqualTo(Role.SUPER_ADMIN);
        assertThat(admin.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(encoder.matches(PASSWORD, admin.getPasswordHash())).isTrue();
    }

    @Test
    void 같은_아이디가_있으면_아무것도_하지_않는다() {
        Admin existing = Admin.register("superadmin", encoder.encode("other"), "기존", Role.SUPER_ADMIN);
        when(repository.findByLoginId(LocalSuperAdminInitializer.LOGIN_ID)).thenReturn(Optional.of(existing));

        initializer.run(new DefaultApplicationArguments());

        verify(repository, never()).save(any());
    }

    @Test
    void local_프로필에서만_빈이_된다() {
        Profile profile = LocalSuperAdminInitializer.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local");
    }
}
