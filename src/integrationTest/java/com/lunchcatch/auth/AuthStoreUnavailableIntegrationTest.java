package com.lunchcatch.auth;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.admin.entity.Admin;
import com.lunchcatch.admin.repository.AdminRepository;
import com.lunchcatch.admin.repository.AdminLoginSessionRepository;
import com.lunchcatch.owner.entity.Owner;
import com.lunchcatch.owner.repository.OwnerRepository;
import com.lunchcatch.owner.repository.OwnerSessionRepository;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 요청마다 토큰을 검증하면서 세션 DB 를 읽지 못하면 401 이 아니라 503 AUTH-002 로 답하는지 실제 입구부터 본다(#1226).
 * 401 을 받은 클라이언트는 재발급을 불러 DB 가 힘든 순간에 요청을 늘리고, AUTH-005 는 남기지 않는 응답이라 장애가 숨는다.
 */
class AuthStoreUnavailableIntegrationTest extends AbstractAuthIntegrationTest {

    private static final String PASSWORD = "password12";

    @Autowired MockMvc mvc;
    @Autowired AdminRepository admins;
    @Autowired OwnerRepository owners;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean OwnerSessionRepository ownerSessions;
    @MockitoSpyBean AdminLoginSessionRepository adminSessions;

    private final List<Long> adminIds = new ArrayList<>();
    private final List<Long> ownerIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        adminIds.forEach(id -> {
            jdbc.update("delete from admin_login_session where admin_id = ?", id);
            jdbc.update("delete from admin where admin_id = ?", id);
        });
        ownerIds.forEach(id -> {
            jdbc.update("delete from owner_session where owner_id = ?", id);
            jdbc.update("delete from owner where owner_id = ?", id);
        });
    }

    @Test
    void 점주_세션_DB_를_읽지_못하면_503_AUTH002_와_Retry_After_로_답한다() throws Exception {
        Cookie[] browser = loginOwner();
        doThrow(new DataAccessResourceFailureException("DB")).when(ownerSessions).findCurrent(anyString(), anyLong());

        mvc.perform(get("/v1/owner/me").cookie(browser))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH-002"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
    }

    @Test
    void 점주_세션이_없으면_지금처럼_401_AUTH005_다() throws Exception {
        Cookie[] browser = loginOwner();
        jdbc.update("delete from owner_session where owner_id = ?", ownerIds.getLast());

        mvc.perform(get("/v1/owner/me").cookie(browser))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 관리자_세션_DB_를_읽지_못하면_503_AUTH002_와_Retry_After_로_답한다() throws Exception {
        Cookie[] browser = loginAdmin();
        doThrow(new DataAccessResourceFailureException("DB")).when(adminSessions).findByAdminIdAndIssuanceVersion(anyLong(), anyLong());

        mvc.perform(get("/v1/admin/admins").cookie(browser))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH-002"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
    }

    private Cookie[] loginAdmin() throws Exception {
        String loginId = "login." + UUID.randomUUID().toString().substring(0, 8);
        Admin admin = admins.saveAndFlush(Admin.register(loginId, passwords.encode(PASSWORD), "관리자", Role.SUPER_ADMIN));
        adminIds.add(admin.getId());
        // 로그인 경로들이 IP 하나의 요청 제한을 같이 써서 다른 테스트와 겹치지 않는 IP 로 부른다
        return mvc.perform(post("/v1/admin/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(request -> {
                            request.setRemoteAddr("198.51.101." + (admin.getId() % 200 + 1));
                            return request;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getCookies();
    }

    private Cookie[] loginOwner() throws Exception {
        Owner owner = owners.saveAndFlush(Owner.create(UUID.randomUUID() + "@example.com", passwords.encode(PASSWORD)));
        ownerIds.add(owner.getId());
        jdbc.update("update owner set status = 'ACTIVE', tutorial_viewed = true where owner_id = ?", owner.getId());
        return mvc.perform(post("/v1/owner/auth/tokens").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(request -> {
                            request.setRemoteAddr("203.0.114." + (owner.getId() % 200 + 1));
                            return request;
                        }))
                .andExpect(status().isOk()).andReturn().getResponse().getCookies();
    }
}
