package com.lunchcatch.owner;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.owner.entity.Owner;
import com.lunchcatch.owner.entity.OwnerStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 관리자 권한과 개인정보 비노출을 HTTP부터 실제 조회·마스킹까지 확인한다.
@Transactional
class AdminOwnerIntegrationTest extends AbstractOwnerIntegrationTest {

    private static final String PATH = "/v1/admin/owners";
    private static final LocalDateTime JOINED_AT = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN"})
    void 관리자만_목록을_조회한다(String role) throws Exception {
        mvc.perform(get(PATH).with(user("admin").roles(role)).param("keyword", Long.toString(Long.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "OWNER"})
    void 일반_사용자와_점주는_목록을_조회할_수_없다(String role) throws Exception {
        mvc.perform(get(PATH).with(user("user").roles(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-006"));
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    @Test
    void 계정_상태별_마스킹과_가게_미등록을_실제_응답에서_확인한다() throws Exception {
        String name = "목록보안" + UUID.randomUUID();
        long active = owner(OwnerStatus.ACTIVE, JOINED_AT);
        store(active, name, "1234567890", true);
        long withdrawn = owner(OwnerStatus.WITHDRAWN, JOINED_AT);
        store(withdrawn, name + "탈퇴", "1234567890", true);
        long draft = owner(OwnerStatus.ONBOARDING, JOINED_AT);
        store(draft, name + "초안", null, false);
        long noStore = owner(OwnerStatus.ONBOARDING, JOINED_AT);

        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")).param("keyword", name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[0].ownerId").value(draft))
                .andExpect(jsonPath("$.data.items[0].businessNo").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].storeRegistered").value(false))
                .andExpect(jsonPath("$.data.items[1].ownerId").value(withdrawn))
                .andExpect(jsonPath("$.data.items[1].storeName").value(name + "탈퇴"))
                .andExpect(jsonPath("$.data.items[1].businessNo").value("***-**-*****"))
                .andExpect(jsonPath("$.data.items[1].lastLoginAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[2].businessNo").value("123-45-*****"))
                .andExpect(jsonPath("$.data.items[2].storeRegistered").value(true))
                .andExpect(jsonPath("$.data.items[2].joinedAt").value("2026-10-01T12:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[2].lastLoginAt").value("2026-10-01T13:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[*].email").isEmpty())
                .andExpect(jsonPath("$.data.items[*].passwordHash").isEmpty())
                .andExpect(jsonPath("$.data.items[*].refreshTokenHash").isEmpty());

        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")).param("keyword", Long.toString(noStore)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].ownerId").value(noStore))
                .andExpect(jsonPath("$.data.items[0].storeName").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].businessNo").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].storeRegistered").value(false));
    }

    @Test
    void 실제_SQL이_가입일과_상태를_함께_거르고_동률_정렬과_20건_페이지를_유지한다() throws Exception {
        String name = "목록필터" + UUID.randomUUID();
        long first = 0;
        long last = 0;
        for (int i = 0; i < 21; i++) {
            last = owner(OwnerStatus.ACTIVE, JOINED_AT);
            if (i == 0) {
                first = last;
            }
            store(last, name + i, null, false);
        }
        store(owner(OwnerStatus.SUSPENDED, JOINED_AT), name + "정지", null, false);
        store(owner(OwnerStatus.ACTIVE, LocalDateTime.of(2026, 10, 2, 0, 0)), name + "다음날", null, false);

        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")).param("keyword", name)
                        .param("status", "ACTIVE").param("joinedFrom", "2026-10-01").param("joinedTo", "2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(jsonPath("$.data.items[0].ownerId").value(last));
        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")).param("keyword", name)
                        .param("status", "ACTIVE").param("joinedFrom", "2026-10-01").param("joinedTo", "2026-10-01")
                        .param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].ownerId").value(first));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,invalid", "status,UNKNOWN", "joinedFrom,invalid"})
    void 잘못된_입력은_400이다(String parameter, String value) throws Exception {
        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")).param(parameter, value))
                .andExpect(status().isBadRequest());
    }

    private long owner(OwnerStatus status, LocalDateTime joinedAt) {
        Owner owner = owners.saveAndFlush(Owner.create(UUID.randomUUID() + "@example.com", "test-only-hash"));
        jdbc.update("""
                update owner set status = ?, created_at = ?, last_login_at = ?,
                    withdrawn_at = ?, suspended_at = ? where owner_id = ?
                """, status.name(), joinedAt, joinedAt.plusHours(1),
                status == OwnerStatus.WITHDRAWN ? joinedAt : null,
                status == OwnerStatus.SUSPENDED ? joinedAt : null, owner.getId());
        return owner.getId();
    }

    private void store(long ownerId, String name, String businessNo, boolean finalized) {
        jdbc.update("""
                insert into store (owner_id, name, category_code, representative_name,
                    business_registration_number, created_at, updated_at)
                values (?, ?, 'KOREAN', '테스트 대표자', ?, ?, ?)
                """, ownerId, name, businessNo, JOINED_AT, JOINED_AT);
        if (finalized) {
            jdbc.update("""
                    update store set finalized = true, finalized_at = ?, business_verification_status = 'VERIFIED',
                        business_verified_at = ?, latitude = 37.5, longitude = 127.0 where owner_id = ?
                    """, JOINED_AT, JOINED_AT, ownerId);
        }
    }
}
