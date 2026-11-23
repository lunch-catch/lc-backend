package com.lunchcatch.billing;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.billing.deduction.dto.ServeIds;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/*
 * 소진을 원장 DEDUCT 대신 판정의 BILLED 단가 합으로 읽는 화면을 실제 MySQL 8.4 에서 HTTP 입구부터 확인한다(#1292).
 *
 * 전환 전처럼 BILLED 판정마다 원장 DEDUCT 를 같은 시각으로 함께 넣는다. 두 사본의 합이 같으므로 바꾸기 전과 같은 값이 나와야 한다.
 * 미과금 판정(NOT_BILLED_BUDGET)도 하나 넣어 소진에 들지 않는 것을 함께 본다.
 * 다른 테스트와 섞이지 않게 먼 미래 영업일과 큰 점주 ID 를 쓴다.
 */
class SpendFromJudgementIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2033, 4, 10);
    private static final LocalDate PREV = DAY.minusDays(1);
    private static final long OWNER = 971_292L;
    private static final long ADMIN = 971_293L;
    private static final long CAMPAIGN = 12L;

    @Autowired WebApplicationContext webContext;
    @Autowired JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        jdbc.update("insert into owner (owner_id, email, password_hash, role, status, tutorial_viewed, "
                + "created_at, updated_at) values (?, 'spend-1292@lunchcatch.test', 'x', 'OWNER', 'ACTIVE', 1, "
                + "now(6), now(6))", OWNER);

        // 충전 대신 FK 가 없는 양수 조정으로 잔액을 만든다. 잔액 계산에는 둘이 같다
        jdbc.update("""
                insert into point_ledger (owner_id, entry_type, source, amount, reason, idempotency_key, created_at, updated_at)
                values (?, 'ADJUST', 'MANUAL', 100000, '시험 잔액', ?, ?, ?)
                """, OWNER, UUID.randomUUID().toString(), PREV.atTime(8, 0), PREV.atTime(8, 0));

        // 전날: 예약 5,000 에서 2,000 을 쓰고 3,000 을 돌려받아 예약 중 포인트에 남지 않는다
        ledger("RESERVE", -5000, PREV, PREV.atStartOfDay().toString());
        billed(PREV, 2000, PREV.atTime(12, 0).toString());
        ledger("RELEASE", 3000, PREV, DAY.atStartOfDay().toString());

        // 오늘: 예약 10,000 에서 1,000 짜리 노출 셋을 과금했고 하나는 예산 소진으로 과금하지 않았다
        ledger("RESERVE", -10000, DAY, DAY.atStartOfDay().toString());
        for (int i = 0; i < 3; i++) {
            billed(DAY, 1000, DAY.atTime(12, i).toString());
        }
        judgement(DAY, "NOT_BILLED_BUDGET", 1000, DAY.atTime(13, 0).toString());
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from point_ledger where owner_id = ?", OWNER);
        jdbc.update("delete from deduction_result where owner_id = ?", OWNER);
        jdbc.update("delete from owner where owner_id = ?", OWNER);
    }

    private void ledger(String type, long amount, LocalDate businessDate, String createdAt) {
        jdbc.update("""
                insert into point_ledger (owner_id, campaign_id, business_date, entry_type, source, amount, created_at, updated_at)
                values (?, ?, ?, ?, 'RECONCILIATION', ?, ?, ?)
                """, OWNER, CAMPAIGN, businessDate, type, amount, createdAt, createdAt);
    }

    // 전환 전처럼 BILLED 판정과 원장 DEDUCT 를 같은 시각으로 함께 쓴다
    private void billed(LocalDate businessDate, long unitPrice, String createdAt) {
        byte[] serveId = judgement(businessDate, "BILLED", unitPrice, createdAt);
        jdbc.update("""
                insert into point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
                values (?, ?, ?, 'DEDUCT', 'REALTIME', ?, ?, ?)
                """, OWNER, CAMPAIGN, serveId, -unitPrice, createdAt, createdAt);
    }

    private byte[] judgement(LocalDate businessDate, String status, long unitPrice, String createdAt) {
        byte[] serveId = ServeIds.toBytes(UUID.randomUUID());
        jdbc.update("""
                insert into deduction_result
                    (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, serveId, CAMPAIGN, OWNER, businessDate, status, unitPrice, createdAt, createdAt);
        return serveId;
    }

    @Test
    void 점주_잔액의_예약_중_포인트는_예약에서_해제와_BILLED_단가_합을_뺀_값이다() throws Exception {
        mvc.perform(get("/v1/owner/points/balance").with(user(new CustomUserDetails(OWNER, Role.OWNER))))
                .andExpect(status().isOk())
                // 100,000 - 5,000 + 3,000 - 10,000. 잔액은 원장 DEDUCT 를 빼고 센다
                .andExpect(jsonPath("$.data.balance").value(88000))
                // 오늘 예약 10,000 - BILLED 3,000. 전날은 해제로 닫혀 0 이고 미과금 판정은 세지 않는다
                .andExpect(jsonPath("$.data.reservedPoint").value(7000));
    }

    @Test
    void 점주_소진_집계는_그날_기록된_BILLED_판정의_단가_합과_건수다() throws Exception {
        mvc.perform(get("/v1/owner/points/spend-summary")
                        .with(user(new CustomUserDetails(OWNER, Role.OWNER)))
                        .param("groupBy", "DAY_CAMPAIGN")
                        .param("from", PREV.toString())
                        .param("to", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].date").value(PREV.toString()))
                .andExpect(jsonPath("$.data.items[0].spentPoint").value(2000))
                .andExpect(jsonPath("$.data.items[0].impressionCount").value(1))
                .andExpect(jsonPath("$.data.items[1].date").value(DAY.toString()))
                .andExpect(jsonPath("$.data.items[1].spentPoint").value(3000))
                .andExpect(jsonPath("$.data.items[1].reservedPoint").value(10000))
                .andExpect(jsonPath("$.data.items[1].impressionCount").value(3));
    }

    /*
     * 원장 DEDUCT 없이 판정만 있는 노출이다. 쓰기 제거(#1294) 뒤의 모양이다.
     * 원장 DEDUCT 를 읽는 조회로 되돌리면 이 노출이 빠져 소진과 예약 중 포인트가 어긋난다.
     */
    @Test
    void DEDUCT_없이_BILLED_판정만_있는_노출도_소진과_예약_중_포인트에_든다() throws Exception {
        judgement(DAY, "BILLED", 500, DAY.atTime(14, 0).toString());

        mvc.perform(get("/v1/owner/points/balance").with(user(new CustomUserDetails(OWNER, Role.OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(88000))
                .andExpect(jsonPath("$.data.reservedPoint").value(6500));
        mvc.perform(get("/v1/owner/points/spend-summary")
                        .with(user(new CustomUserDetails(OWNER, Role.OWNER)))
                        .param("from", DAY.toString())
                        .param("to", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].spentPoint").value(3500))
                .andExpect(jsonPath("$.data.items[0].impressionCount").value(4));
    }

    @Test
    void 점주_소진_집계는_캠페인으로_거를_수_있다() throws Exception {
        mvc.perform(get("/v1/owner/points/spend-summary")
                        .with(user(new CustomUserDetails(OWNER, Role.OWNER)))
                        .param("from", DAY.toString())
                        .param("to", DAY.toString())
                        .param("campaignId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 관리자_변동_목록은_DEDUCT_를_내리지_않고_serveId_는_비어_있다() throws Exception {
        mvc.perform(get("/v1/admin/point-ledger")
                        .with(user(new CustomUserDetails(ADMIN, Role.ADMIN)))
                        .param("ownerId", String.valueOf(OWNER))
                        .param("from", PREV.toString())
                        .param("to", DAY.toString()))
                .andExpect(status().isOk())
                // 조정, 전날 예약과 해제, 오늘 예약의 네 줄이다
                .andExpect(jsonPath("$.data.items", hasSize(4)))
                .andExpect(jsonPath("$.data.items[*].entryType", everyItem(not("DEDUCT"))))
                .andExpect(jsonPath("$.data.items[*].serveId", everyItem(nullValue())));
    }

    @Test
    void 관리자_변동_목록에_DEDUCT_를_종류로_주면_빈_목록이다() throws Exception {
        mvc.perform(get("/v1/admin/point-ledger")
                        .with(user(new CustomUserDetails(ADMIN, Role.ADMIN)))
                        .param("ownerId", String.valueOf(OWNER))
                        .param("type", "DEDUCT")
                        .param("from", PREV.toString())
                        .param("to", DAY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }
}
