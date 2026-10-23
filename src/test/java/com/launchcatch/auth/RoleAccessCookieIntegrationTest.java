package com.launchcatch.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.admin.entity.Admin;
import com.launchcatch.admin.repository.AdminRepository;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.repository.OwnerRepository;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 같은 브라우저에서 관리자와 점주로 차례로 로그인해도 두 화면이 모두 동작하는지 실제 입구부터 본다.
 * 전에는 Access Token 쿠키가 하나라 나중에 로그인한 점주의 토큰이 관리자 토큰을 덮어써 관리자 API 가 403 이었다(#841).
 */
class RoleAccessCookieIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String PASSWORD = "password12";

    @Autowired MockMvc mvc;
    @Autowired AdminRepository admins;
    @Autowired OwnerRepository owners;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;

    private final List<Long> adminIds = new ArrayList<>();
    private final List<Long> ownerIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        adminIds.forEach(id -> jdbc.update("delete from admin where admin_id = ?", id));
        ownerIds.forEach(id -> jdbc.update("delete from owner where owner_id = ?", id));
    }

    @Test
    void 관리자로_로그인한_뒤_점주로_로그인해도_두_역할의_API_를_모두_부른다() throws Exception {
        Cookie[] browser = merge(loginAdmin(), loginOwner());

        mvc.perform(get("/v1/admin/admins").cookie(browser)).andExpect(status().isOk());
        mvc.perform(get("/v1/owner/me").cookie(browser)).andExpect(status().isOk());
    }

    @Test
    void 점주가_로그아웃해도_관리자_쿠키는_지우지_않는다() throws Exception {
        Cookie[] browser = merge(loginAdmin(), loginOwner());

        MockHttpServletResponse logout = mvc.perform(delete("/v1/owner/auth/tokens").cookie(browser))
                .andExpect(status().isNoContent()).andReturn().getResponse();

        assertThat(logout.getCookie("ownerAccessToken").getMaxAge()).isZero();
        assertThat(logout.getCookie("adminAccessToken")).isNull();
        mvc.perform(get("/v1/admin/admins").cookie(browser)).andExpect(status().isOk());
    }

    private Cookie[] loginAdmin() throws Exception {
        String loginId = "login." + UUID.randomUUID().toString().substring(0, 8);
        Admin admin = admins.saveAndFlush(Admin.register(loginId, passwords.encode(PASSWORD), "관리자", Role.SUPER_ADMIN));
        adminIds.add(admin.getId());
        return mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(request -> {
                            request.setRemoteAddr("198.51.100." + (admin.getId() % 200 + 1));
                            return request;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getCookies();
    }

    private Cookie[] loginOwner() throws Exception {
        Owner owner = owners.saveAndFlush(Owner.create(UUID.randomUUID() + "@example.com", passwords.encode(PASSWORD)));
        ownerIds.add(owner.getId());
        jdbc.update("update owner set status = 'ACTIVE', tutorial_viewed = true where owner_id = ?", owner.getId());
        // 로그인 경로들이 IP 하나의 요청 제한을 같이 써서 다른 테스트와 겹치지 않는 IP 로 부른다
        return mvc.perform(post("/v1/owner/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113." + (owner.getId() % 200 + 1));
                            return request;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getCookies();
    }

    // 브라우저처럼 이름과 Path 가 같은 쿠키는 나중 것으로 덮어쓴다
    private static Cookie[] merge(Cookie[]... responses) {
        List<Cookie> jar = new ArrayList<>();
        for (Cookie[] cookies : responses) {
            for (Cookie cookie : cookies) {
                jar.removeIf(saved -> saved.getName().equals(cookie.getName())
                        && String.valueOf(saved.getPath()).equals(String.valueOf(cookie.getPath())));
                jar.add(cookie);
            }
        }
        return jar.toArray(Cookie[]::new);
    }
}
