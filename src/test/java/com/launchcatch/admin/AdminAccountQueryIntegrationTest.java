package com.launchcatch.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

class AdminAccountQueryIntegrationTest extends AbstractAdminIntegrationTest {

    private static final String PATH = "/v1/admin/admins";
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 10, 21, 12, 0);
    private static final Cookie TOKEN = new Cookie("accessToken", "list-token");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private Long requesterId;
    private String prefix;

    @BeforeEach
    void setUp() {
        reset(jwt, versions);
        prefix = UUID.randomUUID().toString().substring(0, 8);
        requesterId = insert(prefix + "-self", "최고 관리자", Role.SUPER_ADMIN, TIME);
        doReturn(true).when(jwt).validateToken("list-token");
        doReturn(requesterId).when(jwt).getId("list-token");
        doReturn(Role.SUPER_ADMIN).when(jwt).getRole("list-token");
        doReturn(TIME).when(jwt).getIssuedAt("list-token");
        doReturn(AdminAccessTokenClaims.forVersion(1L)).when(jwt).getAdditionalClaims("list-token");
        doReturn(true).when(versions).isVersionValid(Role.SUPER_ADMIN, requesterId, 1L);
    }

    @Test
    void 활성_계정을_최근_발급순으로_조회하고_동일_시각에는_PK_내림차순이다() throws Exception {
        insert(prefix + "-old", "일반 관리자", Role.ADMIN, TIME.minusDays(1));
        insert(prefix + "-new", "새 관리자", Role.ADMIN, TIME);
        Long inactive = insert(prefix + "-inactive", "비활성 관리자", Role.ADMIN, TIME.plusDays(1));
        jdbc.update("update admin set status = 'DELETED', deleted_at = ? where admin_id = ?", TIME, inactive);

        mvc.perform(get(PATH).cookie(TOKEN).param("keyword", prefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].loginId").value(prefix + "-new"))
                .andExpect(jsonPath("$.data[1].loginId").value(prefix + "-self"))
                .andExpect(jsonPath("$.data[1].role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data[2].loginId").value(prefix + "-old"))
                .andExpect(jsonPath("$.data[0].name").value("새 관리자"))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[0].length()").value(3));
        assertThat(jdbc.queryForObject("select count(*) from admin where login_id like ?", Integer.class, prefix + "%"))
                .isEqualTo(4);
    }

    @Test
    void ID_또는_이름을_대소문자_구분_없이_부분_검색하고_앞뒤_공백을_제거한다() throws Exception {
        insert(prefix + "-MiXeD-id", "ID 검색 대상", Role.ADMIN, TIME);
        insert(prefix + "-name", prefix + "-MIXED 이름 검색 대상", Role.ADMIN, TIME);

        mvc.perform(get(PATH).cookie(TOKEN).param("keyword", "  " + prefix + "-mIxEd  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].loginId", containsInAnyOrder(prefix + "-MiXeD-id", prefix + "-name")));
    }

    @Test
    void 검색_결과가_없으면_빈_배열이다() throws Exception {
        mvc.perform(get(PATH).cookie(TOKEN).param("keyword", prefix + "-missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 검색어가_없거나_공백뿐이면_본인을_포함한_전체_목록이다() throws Exception {
        insert(prefix + "-other", "일반 관리자", Role.ADMIN, TIME);

        var all = mvc.perform(get(PATH).cookie(TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].loginId", hasItem(prefix + "-self")))
                .andExpect(jsonPath("$.data[*].loginId", hasItem(prefix + "-other")))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get(PATH).cookie(TOKEN).param("keyword", "   "))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(all));
    }

    @Test
    void 와일드카드와_이스케이프_문자는_문자_그대로_검색한다() throws Exception {
        insert(prefix + "-literal", prefix + "!_%", Role.ADMIN, TIME);
        insert(prefix + "-other", prefix + "!abc", Role.ADMIN, TIME);
        mvc.perform(get(PATH).cookie(TOKEN).param("keyword", prefix + "!_%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].loginId").value(prefix + "-literal"));
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 일반_관리자는_403이다() throws Exception {
        doReturn(Role.ADMIN).when(jwt).getRole("list-token");
        doReturn(true).when(versions).isVersionValid(Role.ADMIN, requesterId, 1L);
        mvc.perform(get(PATH).cookie(TOKEN)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void 토큰이_최고_관리자여도_현재_DB_권한이_바뀌었으면_403이다() throws Exception {
        jdbc.update("update admin set role = 'ADMIN' where admin_id = ?", requesterId);
        mvc.perform(get(PATH).cookie(TOKEN)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void 현재_DB_계정이_비활성이면_403이다() throws Exception {
        jdbc.update("update admin set status = 'DELETED', deleted_at = ? where admin_id = ?", TIME, requesterId);
        mvc.perform(get(PATH).cookie(TOKEN)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void 필수_토큰_폐기_확인_저장소가_실패하면_503이다() throws Exception {
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(versions).isVersionValid(Role.SUPER_ADMIN, requesterId, 1L);
        mvc.perform(get(PATH).cookie(TOKEN)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH-002"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from admin where login_id like ?", prefix + "%");
    }

    private Long insert(String loginId, String name, Role role, LocalDateTime createdAt) {
        jdbc.update("insert into admin (login_id, password_hash, name, role, status, created_at, updated_at) "
                        + "values (?, 'unused-test-hash', ?, ?, 'ACTIVE', ?, ?)", loginId, name, role.name(), createdAt, createdAt);
        return jdbc.queryForObject("select admin_id from admin where login_id = ?", Long.class, loginId);
    }
}
