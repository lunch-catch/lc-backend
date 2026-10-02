package com.launchcatch.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/*
 * 역할 넷이 공용으로 쓴다.
 *
 * 옮겨온 쪽은 ROLE_ 권한과 TYPE_ 권한을 둘 다 내보내 회원 토큰이 관리자 API 에 닿는 것을
 * TYPE_ 으로 막았다. 런치캐치는 역할이 단일 축이라 그 구분이 필요 없다. 관리자 경로는
 * ROLE_ADMIN 과 ROLE_SUPER_ADMIN 만 통과시키면 되고, 사용자 토큰에는 그 권한이 애초에 없다.
 */
public class CustomUserDetails implements UserDetails {

    private final Long id;
    private final Role role;

    public CustomUserDetails(Long id, Role role) {
        this.id = id;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.toAuthority()));
    }

    /** 비밀번호는 인증이 끝난 뒤에 쓸 일이 없어 담지 않는다. */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return String.valueOf(id);
    }
}
