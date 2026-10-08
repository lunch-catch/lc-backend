package com.launchcatch.admin.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.launchcatch.auth.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AdminTest {

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
    void 관리자_권한으로_활성_계정을_생성한다(Role role) {
        Admin admin = Admin.register("admin01", "password-hash", "홍길동", role);

        assertThat(admin.getRole()).isEqualTo(role);
        assertThat(admin.getStatus()).isEqualTo(AdminStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "MEMBER"})
    void 관리자_외의_권한은_거부한다(Role role) {
        assertThatThrownBy(() -> Admin.register("admin01", "password-hash", "홍길동", role))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("role 은 ADMIN 또는 SUPER_ADMIN이어야 한다");
    }

    @Test
    void 권한이_없으면_거부한다() {
        assertThatThrownBy(() -> Admin.register("admin01", "password-hash", "홍길동", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("role 은 필수다");
    }
}