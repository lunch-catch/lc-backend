package com.launchcatch.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.store.contract.StoreCampaignStatus;
import com.launchcatch.store.contract.StoreCampaignSummaryCommand;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdate;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class StoreListIntegrationTest extends AbstractStoreIntegrationTest {
    private static final AtomicLong OWNER_IDS = new AtomicLong(940000000);
    private static final String KEYWORD = "store331";
    @Autowired JdbcTemplate jdbc;
    @Autowired StoreCampaignSummaryUpdater summaries;
    @Autowired Clock clock;

    @Test
    void 검색어가_없어도_현재_위치에서_거리순으로_조회한다() throws Exception {
        long near = createStore(KEYWORD + " 가까운 가게", "KOREAN", "10", "10", true);
        long far = createStore(KEYWORD + " 먼 가게", "JAPANESE", "10.1", "10", true);

        mvc.perform(storesAt("10", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].storeId").value(near))
                .andExpect(jsonPath("$.data.items[1].storeId").value(far))
                .andExpect(jsonPath("$.data.items[0].distanceMeters").value(0))
                .andExpect(jsonPath("$.data.items[0].hasActiveCampaign").value(false))
                .andExpect(jsonPath("$.data.items[0].activeCampaign").value((Object) null));
    }

    @Test
    void 다중_필터는_OR로_처리하고_미등록_가게를_제외한다() throws Exception {
        long korean = createStore(KEYWORD + " 한식", "KOREAN", "37", "127", true);
        long japanese = createStore(KEYWORD + " 일식", "JAPANESE", "37", "127", true);
        createStore(KEYWORD + " 카페", "CAFE_DESSERT", "37", "127", true);
        createStore(KEYWORD + " 등록 전", "KOREAN", "37", "127", false);

        String body = mvc.perform(get("/v1/stores").with(user(new CustomUserDetails(1L, Role.MEMBER)))
                        .param("latitude", "37").param("longitude", "127")
                        .param("filter", "KOREAN", "JAPANESE").param("query", KEYWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(ids(body)).containsExactly(korean, japanese);
    }

    @Test
    void 같은_표시_거리여도_실제_거리와_ID로_커서가_이어진다() throws Exception {
        long farther = createStore(KEYWORD + " 2", "KOREAN", "37.000002", "127", true);
        long closer = createStore(KEYWORD + " 1", "KOREAN", "37.000001", "127", true);
        long sameDistance = createStore(KEYWORD + " 3", "KOREAN", "37.000002", "127", true);
        String first = mvc.perform(stores().param("query", KEYWORD).param("pageSize", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].distanceMeters").value(0))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(stores().param("query", KEYWORD).param("pageSize", "1")
                        .param("pageToken", (String) JsonPath.read(first, "$.data.nextPageToken")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String third = mvc.perform(stores().param("query", KEYWORD).param("pageSize", "1")
                        .param("pageToken", (String) JsonPath.read(second, "$.data.nextPageToken")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nextPageToken").value((Object) null))
                .andReturn().getResponse().getContentAsString();

        assertThat(ids(first)).containsExactly(closer);
        assertThat(ids(second)).containsExactly(farther);
        assertThat(ids(third)).containsExactly(sameDistance);
    }

    @Test
    void 계산한_거리를_정수_미터로_반올림하여_반환한다() throws Exception {
        createStore(KEYWORD, "KOREAN", "37.3960000", "127.1135000", true);

        mvc.perform(storesAt("37.3947766", "127.1111967").param("query", KEYWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].distanceMeters").value(245));
    }

    @Test
    void 대표_메뉴_여러_개가_일치해도_가게는_한_번만_반환한다() throws Exception {
        long storeId = createStore("메뉴 검색 대상", "KOREAN", "37", "127", true);
        createMenu(storeId, KEYWORD + " 국수", 1);
        createMenu(storeId, KEYWORD + " 냉국수", 2);

        String body = mvc.perform(stores().param("query", KEYWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(ids(body)).containsExactly(storeId);
    }

    @Test
    void 검색어의_퍼센트와_밑줄을_와일드카드로_해석하지_않는다() throws Exception {
        long literal = createStore(KEYWORD + "%_", "KOREAN", "37", "127", true);
        createStore(KEYWORD + " 다른 가게", "KOREAN", "37", "127", true);

        String body = mvc.perform(stores().param("query", KEYWORD + "%_"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(ids(body)).containsExactly(literal);
    }

    @Test
    void 검색_결과가_없으면_빈_목록과_null_커서를_반환한다() throws Exception {
        mvc.perform(stores().param("query", "존재하지않는store331"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextPageToken").value((Object) null));
    }

    @Test
    void 활성_캠페인이_있어도_먼_가게를_앞으로_옮기지_않는다() throws Exception {
        long near = createStore(KEYWORD + " 가까움", "KOREAN", "37", "127", true);
        long far = createStore(KEYWORD + " 멀리", "KOREAN", "38", "128", true);
        saveSummary(far, LocalDate.now(clock), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null);

        String body = mvc.perform(stores().param("query", KEYWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].hasActiveCampaign").value(false))
                .andExpect(jsonPath("$.data.items[1].hasActiveCampaign").value(true))
                .andExpect(jsonPath("$.data.items[1].activeCampaign.discountType").value("AMOUNT"))
                .andExpect(jsonPath("$.data.items[1].activeCampaign.targetMenu").value((Object) null))
                .andReturn().getResponse().getContentAsString();

        assertThat(ids(body)).containsExactly(near, far);
    }

    @Test
    void MENU_할인의_현재_메뉴명과_가격을_반환한다() throws Exception {
        long storeId = createStore(KEYWORD, "KOREAN", "37", "127", true);
        long menuId = createMenu(storeId, "국수", 1);
        saveSummary(storeId, LocalDate.now(clock), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.MENU, menuId);
        jdbc.update("update store_menu set name = '변경된 국수', price = 12000 where store_menu_id = ?", menuId);

        mvc.perform(stores().param("query", KEYWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].activeCampaign.targetMenu.menuId").value(menuId))
                .andExpect(jsonPath("$.data.items[0].activeCampaign.targetMenu.name").value("변경된 국수"))
                .andExpect(jsonPath("$.data.items[0].activeCampaign.targetMenu.price").value(12000));
    }

    @Test
    void 활성_MENU_캠페인의_메뉴가_누락되면_정상_응답으로_숨기지_않는다() throws Exception {
        long storeId = createStore(KEYWORD, "KOREAN", "37", "127", true);
        saveSummary(storeId, LocalDate.now(clock), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null);
        jdbc.update("update store_campaign_summary set discount_target_type = 'MENU' where store_id = ?", storeId);

        mvc.perform(stores().param("query", KEYWORD)).andExpect(status().isInternalServerError());
    }

    @Test
    void 다른_날짜의_활성_캠페인과_오늘_중단된_캠페인은_표시하지_않는다() throws Exception {
        long past = createStore(KEYWORD + " 어제", "KOREAN", "37", "127", true);
        long future = createStore(KEYWORD + " 내일", "KOREAN", "37", "127", true);
        long paused = createStore(KEYWORD + " 중단", "KOREAN", "37", "127", true);
        saveSummary(past, LocalDate.now(clock).minusDays(1), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null);
        saveSummary(future, LocalDate.now(clock).plusDays(1), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null);
        saveSummary(paused, LocalDate.now(clock), StoreCampaignStatus.PAUSED, StoreDiscountTargetType.ALL, null);

        mvc.perform(stores().param("query", KEYWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[?(@.hasActiveCampaign == true)]").isEmpty());
    }

    @Test
    void 요약을_갱신하면_행을_늘리지_않고_상태와_메뉴를_바꾼다() {
        long storeId = createStore(KEYWORD, "KOREAN", "37", "127", true);
        long menuId = createMenu(storeId, "국수", 1);
        saveSummary(storeId, LocalDate.now(clock), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.MENU, menuId);
        LocalDateTime createdAt = jdbc.queryForObject("select created_at from store_campaign_summary where store_id = ?",
                LocalDateTime.class, storeId);

        saveSummary(storeId, LocalDate.now(clock), StoreCampaignStatus.PAUSED, StoreDiscountTargetType.ALL, null);

        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary where store_id = ?", Long.class, storeId))
                .isEqualTo(1);
        var saved = jdbc.queryForMap("select * from store_campaign_summary where store_id = ?", storeId);
        assertThat(saved.get("campaign_status")).isEqualTo("PAUSED");
        assertThat(saved.get("target_menu_id")).isNull();
        assertThat(jdbc.queryForObject("select created_at from store_campaign_summary where store_id = ?",
                LocalDateTime.class, storeId)).isEqualTo(createdAt);

        saveSummary(storeId, LocalDate.now(clock).plusDays(1), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null);

        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary where store_id = ?", Long.class, storeId))
                .isEqualTo(2);
    }

    @Test
    void 요약_저장은_호출자_트랜잭션이_롤백되면_함께_롤백된다() {
        long storeId = createStore(KEYWORD, "KOREAN", "37", "127", true);
        saveSummary(storeId, LocalDate.now(clock), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null);
        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary where store_id = ?", Long.class, storeId))
                .isEqualTo(1);

        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary where store_id = ?", Long.class, storeId))
                .isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 호출자_트랜잭션이_없으면_요약을_저장하지_않는다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> saveSummary(1, LocalDate.now(clock),
                StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> summaries.updateAll(List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 오백개_요약을_메뉴_조회_한번과_저장_한번으로_처리한다() {
        List<StoreCampaignSummaryUpdate> updates = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            long storeId = createStore(KEYWORD + " 배치 " + i, "KOREAN", "37", "127", true);
            long menuId = createMenu(storeId, "국수", 1);
            updates.add(new StoreCampaignSummaryUpdate(storeId, storeId,
                    summaryCommand(StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.MENU, menuId)));
        }
        long rowsBefore = jdbc.queryForObject("select count(*) from store_campaign_summary", Long.class);
        long insertsBefore = sessionCount("Com_insert");
        long selectsBefore = sessionCount("Com_select");

        summaries.updateAll(updates);

        assertThat(sessionCount("Com_insert") - insertsBefore).isEqualTo(1);
        assertThat(sessionCount("Com_select") - selectsBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary", Long.class))
                .isEqualTo(rowsBefore + 500);
    }

    @Test
    void 일괄_저장은_기존_요약을_갱신하고_새_요약을_생성한다() {
        long existing = createStore(KEYWORD + " 기존", "KOREAN", "37", "127", true);
        long menuId = createMenu(existing, "국수", 1);
        saveSummary(existing, LocalDate.now(clock), StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.MENU, menuId);
        LocalDateTime createdAt = jdbc.queryForObject(
                "select created_at from store_campaign_summary where store_id = ?", LocalDateTime.class, existing);
        long added = createStore(KEYWORD + " 신규", "KOREAN", "37", "127", true);

        summaries.updateAll(List.of(
                new StoreCampaignSummaryUpdate(existing, existing,
                        summaryCommand(StoreCampaignStatus.PAUSED, StoreDiscountTargetType.ALL, null)),
                new StoreCampaignSummaryUpdate(added, added,
                        summaryCommand(StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null))));

        var saved = jdbc.queryForMap("select * from store_campaign_summary where store_id = ?", existing);
        assertThat(saved.get("campaign_status")).isEqualTo("PAUSED");
        assertThat(saved.get("target_menu_id")).isNull();
        assertThat(jdbc.queryForObject("select created_at from store_campaign_summary where store_id = ?",
                LocalDateTime.class, existing)).isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary where store_id in (?, ?)",
                Long.class, existing, added)).isEqualTo(2);
    }

    @Test
    void 일괄_입력에_잘못된_메뉴가_있으면_정상_항목도_저장하지_않는다() {
        long first = createStore(KEYWORD + " 첫 가게", "KOREAN", "37", "127", true);
        long second = createStore(KEYWORD + " 둘째 가게", "KOREAN", "37", "127", true);
        long firstMenu = createMenu(first, "국수", 1);

        assertThatThrownBy(() -> summaries.updateAll(List.of(
                new StoreCampaignSummaryUpdate(first, first,
                        summaryCommand(StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null)),
                new StoreCampaignSummaryUpdate(second, second,
                        summaryCommand(StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.MENU, firstMenu)))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary where store_id in (?, ?)",
                Long.class, first, second)).isZero();
    }

    @Test
    void 일괄_저장은_호출자_트랜잭션과_함께_롤백된다() {
        long first = createStore(KEYWORD + " 첫 가게", "KOREAN", "37", "127", true);
        long second = createStore(KEYWORD + " 둘째 가게", "KOREAN", "37", "127", true);
        summaries.updateAll(List.of(
                new StoreCampaignSummaryUpdate(first, first,
                        summaryCommand(StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null)),
                new StoreCampaignSummaryUpdate(second, second,
                        summaryCommand(StoreCampaignStatus.ACTIVE, StoreDiscountTargetType.ALL, null))));
        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary where store_id in (?, ?)",
                Long.class, first, second)).isEqualTo(2);

        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(jdbc.queryForObject("select count(*) from store_campaign_summary where store_id in (?, ?)",
                Long.class, first, second)).isZero();
    }

    @Test
    void 경도_마이너스180도_유효한_좌표로_조회한다() throws Exception {
        long storeId = createStore(KEYWORD, "KOREAN", "0", "-180", true);

        mvc.perform(get("/v1/stores").with(user(new CustomUserDetails(1L, Role.MEMBER)))
                        .param("latitude", "0").param("longitude", "-180")
                        .param("filter", "ALL").param("query", KEYWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].storeId").value(storeId))
                .andExpect(jsonPath("$.data.items[0].distanceMeters").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\u2003"})
    void 빈_검색어를_거부한다(String query) throws Exception {
        mvc.perform(stores().param("query", query)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101"})
    void 페이지_크기의_범위를_검증한다(String size) throws Exception {
        mvc.perform(stores().param("pageSize", size)).andExpect(status().isBadRequest());
    }

    @Test
    void 좌표와_필터는_필수다() throws Exception {
        mvc.perform(get("/v1/stores").with(user(new CustomUserDetails(1L, Role.MEMBER)))
                        .param("longitude", "127").param("filter", "ALL"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/stores").with(user(new CustomUserDetails(1L, Role.MEMBER)))
                        .param("latitude", "37").param("filter", "ALL"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/stores").with(user(new CustomUserDetails(1L, Role.MEMBER)))
                        .param("latitude", "37").param("longitude", "127"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-91", "91", "NaN"})
    void 위도_범위를_검증한다(String latitude) throws Exception {
        mvc.perform(storesAt(latitude, "127")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-181", "181", "Infinity"})
    void 경도_범위를_검증한다(String longitude) throws Exception {
        mvc.perform(storesAt("37", longitude)).andExpect(status().isBadRequest());
    }

    @Test
    void ALL과_개별_카테고리의_혼용을_거부한다() throws Exception {
        mvc.perform(stores().param("filter", "KOREAN")).andExpect(status().isBadRequest());
    }

    @Test
    void 같은_필터를_반복해도_조회_조건과_결과는_변하지_않는다() throws Exception {
        long storeId = createStore(KEYWORD, "KOREAN", "37", "127", true);

        String body = mvc.perform(stores().param("filter", "ALL").param("query", KEYWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(ids(body)).containsExactly(storeId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UNKNOWN"})
    void 빈_필터와_잘못된_카테고리를_거부한다(String filter) throws Exception {
        mvc.perform(get("/v1/stores").with(user(new CustomUserDetails(1L, Role.MEMBER)))
                        .param("latitude", "37").param("longitude", "127").param("filter", filter))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 잘못된_커서를_거부한다() throws Exception {
        mvc.perform(stores().param("pageToken", "invalid-token")).andExpect(status().isBadRequest());
    }

    @Test
    void 인증이_없으면_401이다() throws Exception {
        mvc.perform(get("/v1/stores")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "ADMIN", "SUPER_ADMIN"})
    void MEMBER가_아니면_403이다(String role) throws Exception {
        mvc.perform(get("/v1/stores").with(user(new CustomUserDetails(1L, Role.valueOf(role)))))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder stores() {
        return storesAt("37", "127");
    }

    private MockHttpServletRequestBuilder storesAt(String latitude, String longitude) {
        return get("/v1/stores").with(user(new CustomUserDetails(1L, Role.MEMBER)))
                .param("latitude", latitude).param("longitude", longitude).param("filter", "ALL");
    }

    private static List<Long> ids(String body) {
        List<Number> values = JsonPath.read(body, "$.data.items[*].storeId");
        return values.stream().map(Number::longValue).toList();
    }

    private long createStore(String name, String category, String latitude, String longitude, boolean finalized) {
        long ownerId = OWNER_IDS.incrementAndGet();
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("""
                insert into store(owner_id, name, category_code, representative_name, latitude, longitude,
                    business_verification_status, business_verified_at, finalized, finalized_at, created_at, updated_at)
                values (?, ?, ?, '대표자', ?, ?, 'VERIFIED', ?, ?, ?, ?, ?)
                """, ownerId, name, category, latitude, longitude, now, finalized, finalized ? now : null, now, now);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
    }

    private long createMenu(long storeId, String name, int order) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("""
                insert into store_menu(store_id, name, price, sort_order, created_at, updated_at)
                values (?, ?, 10000, ?, ?, ?)
                """, storeId, name, order, now, now);
        return jdbc.queryForObject("select store_menu_id from store_menu where store_id = ? and sort_order = ?",
                Long.class, storeId, order);
    }

    private void saveSummary(long storeId, LocalDate date, StoreCampaignStatus status,
            StoreDiscountTargetType targetType, Long menuId) {
        StoreDiscountType discountType = targetType == StoreDiscountTargetType.MENU
                ? StoreDiscountType.PERCENT : StoreDiscountType.AMOUNT;
        int value = targetType == StoreDiscountTargetType.MENU ? 20 : 3000;
        summaries.update(storeId, storeId, new StoreCampaignSummaryCommand(date, status, targetType, discountType,
                value, menuId, LocalTime.of(11, 30), LocalTime.of(15, 0)));
    }

    private StoreCampaignSummaryCommand summaryCommand(StoreCampaignStatus status,
            StoreDiscountTargetType targetType, Long menuId) {
        return new StoreCampaignSummaryCommand(LocalDate.now(clock), status, targetType, StoreDiscountType.AMOUNT,
                3000, menuId, LocalTime.of(11, 30), LocalTime.of(15, 0));
    }

    private long sessionCount(String variable) {
        return Long.parseLong((String) jdbc.queryForMap("show session status like ?", variable).get("Value"));
    }
}
