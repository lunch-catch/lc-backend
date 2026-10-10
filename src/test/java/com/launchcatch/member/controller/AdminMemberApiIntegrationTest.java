package com.launchcatch.member.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.launchcatch.auth.Role;
import com.launchcatch.auth.jwt.JwtTokenProvider;
import com.launchcatch.member.entity.Member;
import com.launchcatch.member.repository.MemberRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * /v1/admin/** 를 주장하는 필터 체인이 아직 없어서 @PreAuthorize 가 유일한 역할 방어선이다.
 * ADMIN 토큰을 발급하는 경로가 없어 JwtTokenProvider 로 직접 만든다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminMemberApiIntegrationTest {

    private static final String URL = "/v1/admin/members";

    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired MemberRepository memberRepository;
    @Autowired EntityManager entityManager;

    private Member older;
    private Member newer;
    private Member lastSaved;

    @BeforeEach
    void setUp() {
        older = save("kakao-1", "점심헌터", LocalDateTime.of(2026, 10, 1, 0, 0));
        newer = save("kakao-2", "100%할인", LocalDateTime.of(2026, 10, 7, 23, 59, 59));
        save("kakao-3", "a_b", LocalDateTime.of(2026, 10, 3, 12, 0));
        save("kakao-4", "axb", LocalDateTime.of(2026, 10, 4, 12, 0));
        lastSaved = save("kakao-5", "!느낌표", LocalDateTime.of(2026, 10, 5, 12, 0));
        entityManager.flush();
        entityManager.clear();
    }

    private Member save(String providerUserId, String nickname, LocalDateTime createdAt) {
        Member member = memberRepository.save(Member.create(providerUserId, nickname, null));
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE member SET created_at = :at WHERE member_id = :id")
                .setParameter("at", createdAt)
                .setParameter("id", member.getId())
                .executeUpdate();
        return member;
    }

    private ResultActions getAs(Role role, String query) throws Exception {
        Long subjectId = 1L;
        if (role == Role.OWNER) {
            var owner = com.launchcatch.owner.entity.Owner.create("owner-role-test@example.com", "hash");
            entityManager.persist(owner);
            entityManager.flush();
            subjectId = owner.getId();
        }
        String token = jwtTokenProvider.createAccessToken(subjectId, role);
        return mockMvc.perform(get(URL + query).cookie(new Cookie("accessToken", token)));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void 회원_토큰은_403이다() throws Exception {
        getAs(Role.MEMBER, "").andExpect(status().isForbidden());
    }

    @Test
    void 점주_토큰도_403이다() throws Exception {
        getAs(Role.OWNER, "").andExpect(status().isForbidden());
    }

    @Test
    void 관리자와_최고_관리자는_조회할_수_있다() throws Exception {
        getAs(Role.ADMIN, "").andExpect(status().isOk());
        getAs(Role.SUPER_ADMIN, "").andExpect(status().isOk());
    }

    @Test
    void 최근_가입순_같으면_번호_큰_순으로_정렬한다() throws Exception {
        getAs(Role.ADMIN, "")
                .andExpect(jsonPath("$.data.items[0].memberId").value(newer.getId()))
                .andExpect(jsonPath("$.data.items[4].memberId").value(older.getId()))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    void 응답에_제공자_사용자_번호를_내리지_않는다() throws Exception {
        getAs(Role.ADMIN, "")
                .andExpect(jsonPath("$.data.items[0].providerUserId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].joinedAt").value("2026-10-07T23:59:59+09:00"));
    }

    @Test
    void 가입일_범위는_양끝_날짜를_포함한다() throws Exception {
        getAs(Role.ADMIN, "?joinedFrom=2026-10-01&joinedTo=2026-10-07")
                .andExpect(jsonPath("$.data.totalElements").value(5));
        getAs(Role.ADMIN, "?joinedFrom=2026-10-07&joinedTo=2026-10-07")
                .andExpect(jsonPath("$.data.totalElements").value(1));
        getAs(Role.ADMIN, "?joinedFrom=2026-10-02&joinedTo=2026-10-06")
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    /*
     * keyword 는 쿼리 문자열이 아니라 param 으로 넘긴다.
     * get(urlTemplate) 은 템플릿을 인코딩하므로 "%25" 를 적으면 "%2525" 가 되어 서버가 "%25" 라는 글자를 받는다.
     */
    private ResultActions searchKeyword(String searchType, String keyword) throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, Role.ADMIN);
        return mockMvc.perform(get(URL)
                .param("searchType", searchType)
                .param("keyword", keyword)
                .cookie(new Cookie("accessToken", token)));
    }

    @Test
    void 닉네임은_접두사로_찾고_퍼센트와_밑줄과_느낌표는_글자로_다룬다() throws Exception {
        searchKeyword("NICKNAME", "점심").andExpect(jsonPath("$.data.totalElements").value(1));
        searchKeyword("NICKNAME", "헌터").andExpect(jsonPath("$.data.totalElements").value(0));
        searchKeyword("NICKNAME", "%").andExpect(jsonPath("$.data.totalElements").value(0));
        searchKeyword("NICKNAME", "%할인").andExpect(jsonPath("$.data.totalElements").value(0));
        searchKeyword("NICKNAME", "100%").andExpect(jsonPath("$.data.totalElements").value(1));
        searchKeyword("NICKNAME", "a_").andExpect(jsonPath("$.data.totalElements").value(1));
        searchKeyword("NICKNAME", "!").andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 회원_번호_검색은_번호가_일치하는_회원을_찾는다() throws Exception {
        getAs(Role.ADMIN, "?searchType=MEMBER_ID&keyword=" + older.getId())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].nickname").value("점심헌터"));
    }

    @Test
    void 숫자_닉네임은_닉네임_검색으로_찾고_회원_번호_검색으로는_찾지_않는다() throws Exception {
        save("kakao-6", "9999999", LocalDateTime.of(2026, 10, 6, 12, 0));
        entityManager.flush();
        entityManager.clear();

        searchKeyword("NICKNAME", "9999999").andExpect(jsonPath("$.data.totalElements").value(1));
        searchKeyword("MEMBER_ID", "9999999").andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 페이지_크기를_지정할_수_있다() throws Exception {
        getAs(Role.ADMIN, "?size=2")
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    void 회원_번호순으로_정렬한다() throws Exception {
        getAs(Role.ADMIN, "?sortBy=memberId&sortDir=asc")
                .andExpect(jsonPath("$.data.items[0].memberId").value(older.getId()))
                .andExpect(jsonPath("$.data.items[4].memberId").value(lastSaved.getId()));
        getAs(Role.ADMIN, "?sortBy=memberId")
                .andExpect(jsonPath("$.data.items[0].memberId").value(lastSaved.getId()));
    }

    @Test
    void 가입일_오름차순이면_가장_먼저_가입한_회원이_앞이다() throws Exception {
        getAs(Role.ADMIN, "?sortBy=joinedAt&sortDir=asc")
                .andExpect(jsonPath("$.data.items[0].memberId").value(older.getId()))
                .andExpect(jsonPath("$.data.items[4].memberId").value(newer.getId()));
    }

    @Test
    void 닉네임순_한글은_영문과_기호_뒤에_온다() throws Exception {
        getAs(Role.ADMIN, "?sortBy=nickname&sortDir=desc")
                .andExpect(jsonPath("$.data.items[0].nickname").value("점심헌터"));
        getAs(Role.ADMIN, "?sortBy=nickname&sortDir=asc")
                .andExpect(jsonPath("$.data.items[4].nickname").value("점심헌터"));
    }

    @Test
    void 마지막_로그인순_정렬은_기록이_없는_회원을_방향과_관계없이_맨_뒤에_둔다() throws Exception {
        entityManager.createNativeQuery("UPDATE member SET last_login_at = :at WHERE member_id = :id")
                .setParameter("at", LocalDateTime.of(2026, 10, 2, 10, 0))
                .setParameter("id", older.getId())
                .executeUpdate();
        entityManager.createNativeQuery("UPDATE member SET last_login_at = :at WHERE member_id = :id")
                .setParameter("at", LocalDateTime.of(2026, 10, 7, 10, 0))
                .setParameter("id", newer.getId())
                .executeUpdate();
        entityManager.clear();

        getAs(Role.ADMIN, "?sortBy=lastLoginAt&sortDir=asc")
                .andExpect(jsonPath("$.data.items[0].memberId").value(older.getId()))
                .andExpect(jsonPath("$.data.items[1].memberId").value(newer.getId()));
        getAs(Role.ADMIN, "?sortBy=lastLoginAt&sortDir=desc")
                .andExpect(jsonPath("$.data.items[0].memberId").value(newer.getId()))
                .andExpect(jsonPath("$.data.items[1].memberId").value(older.getId()));
    }

    @Test
    void 상태로_거른다() throws Exception {
        getAs(Role.ADMIN, "?status=WITHDRAWN").andExpect(jsonPath("$.data.totalElements").value(0));
        getAs(Role.ADMIN, "?status=ACTIVE").andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    void 결과가_없으면_빈_목록이다() throws Exception {
        getAs(Role.ADMIN, "?page=9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 잘못된_요청은_400이다() throws Exception {
        getAs(Role.ADMIN, "?page=-1").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?joinedFrom=2026-10-08&joinedTo=2026-10-07").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?joinedFrom=20261001").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?status=UNKNOWN").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?size=0").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?size=101").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?sortBy=createdAt").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?sortDir=DESC").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?searchType=UNKNOWN&keyword=a").andExpect(status().isBadRequest());
        getAs(Role.ADMIN, "?searchType=MEMBER_ID&keyword=abc").andExpect(status().isBadRequest());
    }

    @Test
    void 검색_종류_없이_키워드만_주면_400이다() throws Exception {
        searchKeywordOnly("점심").andExpect(status().isBadRequest());
    }

    private ResultActions searchKeywordOnly(String keyword) throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L, Role.ADMIN);
        return mockMvc.perform(get(URL).param("keyword", keyword).cookie(new Cookie("accessToken", token)));
    }
}
