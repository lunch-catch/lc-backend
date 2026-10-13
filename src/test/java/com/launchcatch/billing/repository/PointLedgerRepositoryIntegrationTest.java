package com.launchcatch.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.dto.AdminLedgerItem;
import com.launchcatch.billing.dto.BalanceStep;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.service.AdminPointLedgerService;
import com.launchcatch.global.response.CursorPageResponse;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 원장 조회 쿼리를 실제 MySQL 8.4 에서 검증한다.
 * 합계의 DEDUCT 제외, 시각 범위 경계, 커서 페이지, 결제 조인, 바이너리 서빙 ID 매핑은 목으로 확인할 수 없다.
 * 행은 JDBC 로 직접 넣는다. 엔티티에는 CHARGE 팩터리만 있고, 나머지 종류를 기록하는 쪽은 아직 없다.
 */
@Testcontainers
@SpringBootTest
@Transactional
class PointLedgerRepositoryIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"));

    private static final String DUMMY_JWT_SECRET =
            "test-only-secret-not-used-anywhere-else-0123456789abcdef";

    private static final Long OWNER = 1L;
    private static final Long OTHER_OWNER = 2L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime DAY_START = DAY.atStartOfDay();
    private static final LocalDateTime DAY_END = DAY.plusDays(1).atStartOfDay();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("jwt.secret", () -> DUMMY_JWT_SECRET);
    }

    @Autowired
    private PointLedgerRepository repository;

    @Autowired
    private AdminPointLedgerService adminService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID serveA;

    /*
     * 점주 1 의 하루다. 전날 18:20 에 3만 충전, 00:00:05 에 1만 예약, 09:00 에 같은 시각의 차감 둘(300, 200),
     * 13:30 에 9500 해제, 14:00 에 100 양수 조정이다. 점주 2 의 충전 하나는 섞이면 안 되는 대조군이다.
     */
    @BeforeEach
    void seed() {
        serveA = UUID.randomUUID();
        long payment = payment(OWNER, "order-1", "tgen-1");
        long otherPayment = payment(OTHER_OWNER, "order-2", "tgen-2");

        ledger(OWNER, "CHARGE", "REALTIME", 30000, null, null, payment, null, null,
                LocalDateTime.of(2026, 9, 30, 18, 20));
        ledger(OWNER, "RESERVE", "RECONCILIATION", -10000, 12L, DAY, null, null, null,
                LocalDateTime.of(2026, 10, 1, 0, 0, 5));
        ledger(OWNER, "DEDUCT", "REALTIME", -300, 12L, null, null, serveA, null,
                LocalDateTime.of(2026, 10, 1, 9, 0));
        ledger(OWNER, "DEDUCT", "REALTIME", -200, 12L, null, null, UUID.randomUUID(), null,
                LocalDateTime.of(2026, 10, 1, 9, 0));
        ledger(OWNER, "RELEASE", "RECONCILIATION", 9500, 12L, DAY, null, null, null,
                LocalDateTime.of(2026, 10, 1, 13, 30));
        ledger(OWNER, "ADJUST", "MANUAL", 100, null, null, null, null, "과오 차감 정정",
                LocalDateTime.of(2026, 10, 1, 14, 0));
        ledger(OTHER_OWNER, "CHARGE", "REALTIME", 999, null, null, otherPayment, null, null,
                LocalDateTime.of(2026, 10, 1, 10, 0));
    }

    private long payment(Long ownerId, String orderId, String pgTid) {
        jdbc.update("""
                insert into payment (owner_id, order_id, pg_tid, amount, status, approved_at, created_at, updated_at)
                values (?, ?, ?, 30000, 'APPROVED', '2026-09-30 18:20:00', '2026-09-30 18:19:00', '2026-09-30 18:20:00')
                """, ownerId, orderId, pgTid);
        return jdbc.queryForObject("select payment_id from payment where order_id = ?", Long.class, orderId);
    }

    private void ledger(Long ownerId, String type, String source, long amount, Long campaignId,
                        LocalDate businessDate, Long paymentId, UUID serveId, String reason, LocalDateTime at) {
        jdbc.update("""
                insert into point_ledger (owner_id, campaign_id, serve_id, payment_id, business_date,
                                          entry_type, source, amount, reason, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ownerId, campaignId, serveId == null ? null : toBytes(serveId), paymentId, businessDate,
                type, source, amount, reason, at, at);
    }

    private static byte[] toBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    @Test
    @DisplayName("잔액은 DEDUCT 를 빼고 부호가 있는 저장값을 더하며 다른 점주의 행을 섞지 않는다")
    void 잔액은_DEDUCT를_빼고_다른_점주의_행을_섞지_않는다() {
        assertThat(repository.sumBalance(OWNER)).isEqualTo(29600);
        assertThat(repository.sumBalance(OTHER_OWNER)).isEqualTo(999);
        assertThat(repository.sumBalance(99L)).isZero();
    }

    @Test
    @DisplayName("기간 시작 전 잔액은 시작 시각 이전 행만 더한다")
    void 기간_시작_전_잔액은_시작_시각_이전_행만_더한다() {
        assertThat(repository.sumBalanceBefore(OWNER, DAY_START)).isEqualTo(30000);
        assertThat(repository.sumBalanceBefore(OWNER, LocalDateTime.of(2026, 10, 1, 0, 0, 5))).isEqualTo(30000);
        assertThat(repository.sumBalanceBefore(OWNER, LocalDateTime.of(2026, 10, 1, 0, 0, 6))).isEqualTo(20000);
    }

    @Test
    @DisplayName("영업일과 기록 시각 범위의 합계는 경계를 지킨다")
    void 영업일과_기록_시각_범위의_합계는_경계를_지킨다() {
        assertThat(repository.sumByBusinessDate(OWNER, EntryType.RESERVE, DAY)).isEqualTo(-10000);
        assertThat(repository.sumByBusinessDate(OWNER, EntryType.RELEASE, DAY)).isEqualTo(9500);
        assertThat(repository.sumByBusinessDate(OWNER, EntryType.RESERVE, DAY.plusDays(1))).isZero();
        assertThat(repository.sumByCreatedAt(OWNER, EntryType.DEDUCT, DAY_START, DAY_END)).isEqualTo(-500);
        assertThat(repository.sumByCreatedAt(OWNER, EntryType.DEDUCT, DAY_END, DAY_END.plusDays(1))).isZero();
        assertThat(repository.sumByCreatedAt(OWNER, EntryType.DEDUCT, DAY_START,
                LocalDateTime.of(2026, 10, 1, 9, 0))).isZero();
    }

    @Test
    @DisplayName("점주 원장은 DEDUCT 없이 최근 순으로 읽고 커서로 이어 읽는다")
    void 점주_원장은_DEDUCT_없이_최근_순으로_읽고_커서로_이어_읽는다() {
        List<EntryType> visible = List.of(EntryType.CHARGE, EntryType.RESERVE, EntryType.RELEASE,
                EntryType.REFUND, EntryType.ADJUST);

        List<LedgerRow> first = repository.findOwnerLedger(
                new OwnerLedgerCondition(OWNER, visible, null, null, null, null, null, 2));
        LedgerRow last = first.get(1);
        List<LedgerRow> second = repository.findOwnerLedger(new OwnerLedgerCondition(
                OWNER, visible, null, null, null, last.createdAt(), last.id(), 10));

        assertThat(first).extracting(LedgerRow::entryType).containsExactly(EntryType.ADJUST, EntryType.RELEASE);
        assertThat(second).extracting(LedgerRow::entryType).containsExactly(EntryType.RESERVE, EntryType.CHARGE);
        assertThat(second.get(1).paymentId()).isNotNull();
        assertThat(second.get(0).businessDate()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("점주 원장은 종류, 기간, 캠페인 조건으로 좁힌다")
    void 점주_원장은_종류_기간_캠페인_조건으로_좁힌다() {
        List<EntryType> reserveAndRelease = List.of(EntryType.RESERVE, EntryType.RELEASE);

        assertThat(repository.findOwnerLedger(new OwnerLedgerCondition(
                OWNER, reserveAndRelease, null, null, 12L, null, null, 10))).hasSize(2);
        assertThat(repository.findOwnerLedger(new OwnerLedgerCondition(
                OWNER, reserveAndRelease, null, null, 13L, null, null, 10))).isEmpty();
        assertThat(repository.findOwnerLedger(new OwnerLedgerCondition(
                OWNER, List.of(EntryType.CHARGE), DAY_START, DAY_END, null, null, null, 10))).isEmpty();
        assertThat(repository.findOwnerLedger(new OwnerLedgerCondition(
                OWNER, List.of(EntryType.CHARGE), null, DAY_START, null, null, null, 10))).hasSize(1);
    }

    @Test
    @DisplayName("소진 집계용 쿼리는 하루 DEDUCT 와 영업일 RESERVE 를 캠페인별로 묶는다")
    void 소진_집계용_쿼리는_하루_DEDUCT와_영업일_RESERVE를_캠페인별로_묶는다() {
        List<DeductSumRow> deducted = repository.sumDeductedByCampaign(OWNER, DAY_START, DAY_END, null);
        List<ReserveSumRow> reserved = repository.sumReservedByBusinessDate(OWNER, DAY, DAY, null);

        assertThat(deducted).containsExactly(new DeductSumRow(12L, -500L, 2L));
        assertThat(reserved).containsExactly(new ReserveSumRow(DAY, 12L, -10000L));
        assertThat(repository.sumDeductedByCampaign(OWNER, DAY_START, DAY_END, 13L)).isEmpty();
        assertThat(repository.sumReservedByBusinessDate(OWNER, DAY, DAY, 13L)).isEmpty();
    }

    @Test
    @DisplayName("관리자 원장은 여섯 종류를 시간순으로 읽고 결제 조인으로 PG 거래 ID 를 가져온다")
    void 관리자_원장은_모든_종류를_시간순으로_읽고_PG_거래_ID를_가져온다() {
        AdminLedgerCondition all = new AdminLedgerCondition(
                OWNER, null, LocalDateTime.of(2026, 9, 30, 0, 0), DAY_END, null, true, null, null, 100);

        List<AdminLedgerRow> rows = repository.findAdminLedger(all);

        assertThat(rows).hasSize(6);
        assertThat(rows).extracting(AdminLedgerRow::entryType).containsExactly(
                EntryType.CHARGE, EntryType.RESERVE, EntryType.DEDUCT, EntryType.DEDUCT,
                EntryType.RELEASE, EntryType.ADJUST);
        assertThat(rows.get(0).pgTid()).isEqualTo("tgen-1");
        assertThat(rows.get(1).pgTid()).isNull();
        assertThat(rows.get(2).serveId()).isEqualTo(serveA);
        assertThat(rows.get(2).id()).isLessThan(rows.get(3).id());
        assertThat(rows.get(5).reason()).isEqualTo("과오 차감 정정");
    }

    @Test
    @DisplayName("관리자 원장은 종류와 캠페인 필터를 유지하며 같은 시각의 행을 커서로 이어 읽는다")
    void 관리자_원장은_필터를_유지하며_같은_시각의_행을_커서로_이어_읽는다() {
        AdminLedgerCondition deducts = new AdminLedgerCondition(
                OWNER, EntryType.DEDUCT, DAY_START, DAY_END, 12L, false, null, null, 1);

        AdminLedgerRow first = repository.findAdminLedger(deducts).get(0);
        List<AdminLedgerRow> next = repository.findAdminLedger(new AdminLedgerCondition(
                OWNER, EntryType.DEDUCT, DAY_START, DAY_END, 12L, false, first.createdAt(), first.id(), 1));

        assertThat(next).hasSize(1);
        assertThat(next.get(0).createdAt()).isEqualTo(first.createdAt());
        assertThat(next.get(0).id()).isLessThan(first.id());
        assertThat(repository.findAdminLedger(new AdminLedgerCondition(
                OWNER, EntryType.DEDUCT, DAY_START, DAY_END, 13L, false, null, null, 10))).isEmpty();
    }

    @Test
    @DisplayName("잔액을 바꾸는 줄의 목록은 DEDUCT 를 빼고 시간순이다")
    void 잔액을_바꾸는_줄의_목록은_DEDUCT를_빼고_시간순이다() {
        List<BalanceStep> steps = repository.findBalanceSteps(
                OWNER, LocalDateTime.of(2026, 9, 30, 0, 0), DAY_END);

        assertThat(steps).extracting(BalanceStep::amount).containsExactly(30000L, -10000L, 9500L, 100L);
    }

    @Test
    @DisplayName("합계용 쿼리는 CHARGE 계열과 영업일별 예약 해제를 따로 읽는다")
    void 합계용_쿼리는_CHARGE_계열과_영업일별_예약_해제를_따로_읽는다() {
        List<FlowRow> flows = repository.findFlowRows(OWNER, LocalDateTime.of(2026, 9, 30, 0, 0), DAY_END);
        List<BusinessDateSumRow> reservations = repository.sumReserveAndReleaseByBusinessDate(OWNER, DAY, DAY);

        assertThat(flows).extracting(FlowRow::entryType).containsExactly(EntryType.CHARGE, EntryType.ADJUST);
        assertThat(reservations).containsExactlyInAnyOrder(
                new BusinessDateSumRow(DAY, EntryType.RESERVE, -10000L),
                new BusinessDateSumRow(DAY, EntryType.RELEASE, 9500L));
    }

    @Test
    @DisplayName("관리자 서비스는 실제 DB 에서도 DEDUCT 행의 잔액을 직전 값으로 둔다")
    void 관리자_서비스는_실제_DB에서도_DEDUCT_행의_잔액을_직전_값으로_둔다() {
        CursorPageResponse<AdminLedgerItem> page = adminService.search(
                OWNER, null, LocalDate.of(2026, 9, 30), DAY, null, null, 20, "asc");

        assertThat(page.items()).extracting(AdminLedgerItem::entryType).containsExactly(
                EntryType.CHARGE, EntryType.RESERVE, EntryType.DEDUCT, EntryType.DEDUCT,
                EntryType.RELEASE, EntryType.ADJUST);
        assertThat(page.items()).extracting(AdminLedgerItem::balanceBefore)
                .containsExactly(0L, 30000L, 20000L, 20000L, 20000L, 29500L);
        assertThat(page.items()).extracting(AdminLedgerItem::balanceAfter)
                .containsExactly(30000L, 20000L, 20000L, 20000L, 29500L, 29600L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"asc", "desc"})
    void 관리자_원장은_양방향으로_같은_시각의_페이지_경계를_중복과_누락_없이_넘긴다(String direction) {
        LocalDate from = LocalDate.of(2026, 9, 30);
        CursorPageResponse<AdminLedgerItem> all = adminService.search(OWNER, null, from, DAY, null, null, 100, direction);

        CursorPageResponse<AdminLedgerItem> first = adminService.search(OWNER, null, from, DAY, null, null, 3, direction);
        CursorPageResponse<AdminLedgerItem> second =
                adminService.search(OWNER, null, from, DAY, null, first.nextPageToken(), 3, direction);

        assertThat(first.items()).hasSize(3);
        assertThat(second.items()).hasSize(3);
        assertThat(first.nextPageToken()).isNotNull();
        assertThat(second.nextPageToken()).isNull();
        assertThat(first.items().get(2).createdAt()).isEqualTo(second.items().get(0).createdAt());
        assertThat(java.util.stream.Stream.concat(first.items().stream(), second.items().stream()).toList())
                .containsExactlyElementsOf(all.items());
    }
}
