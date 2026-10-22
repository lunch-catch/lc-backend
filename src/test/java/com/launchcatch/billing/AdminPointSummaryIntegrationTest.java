package com.launchcatch.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.billing.dto.AdminPointSummaryItem;
import com.launchcatch.billing.dto.ServeIds;
import com.launchcatch.billing.dto.SummaryPeriod;
import com.launchcatch.billing.service.AdminPointSummaryService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 점주별 합계의 정정 귀속을 실제 MySQL 8.4 에서 확인한다. 입구는 AdminPointSummaryService.summarize 다.
 * 다른 테스트와 섞이지 않게 먼 미래 영업일과 큰 점주 ID 를 쓴다.
 */
class AdminPointSummaryIntegrationTest extends AbstractBillingIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2032, 3, 10);
    private static final long OWNER = 970_000L;

    @Autowired AdminPointSummaryService summary;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("delete from point_ledger where owner_id = ?", OWNER);
        jdbc.update("delete from deduction_result where owner_id = ?", OWNER);
    }

    private void adjust(long amount, Long campaign, LocalDate target, String createdAt) {
        jdbc.update("""
                insert into point_ledger (owner_id, campaign_id, adjust_target_date, entry_type, source, amount,
                                          reason, idempotency_key, created_at, updated_at)
                values (?, ?, ?, 'ADJUST', 'MANUAL', ?, '정정', ?, ?, ?)
                """, OWNER, campaign, target, amount, UUID.randomUUID().toString(), createdAt, createdAt);
    }

    private AdminPointSummaryItem item(String label) {
        List<AdminPointSummaryItem> items =
                summary.summarize(OWNER, SummaryPeriod.DAILY, DAY.minusDays(1), DAY.plusDays(1), null).items();
        return items.stream().filter(i -> i.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void 정정은_정정_대상_영업일의_구간에_들어가고_대상이_없는_조정은_기록한_날에_들어간다() {
        // 3일 뒤에 기록했지만 DAY 의 과금을 고친 정정이다. 기간 밖에서 기록돼도 대상이 기간 안이면 들어간다
        adjust(1000L, 12L, DAY, "2032-03-13 09:00:00");
        // 대상이 기간 밖이면 기록한 날이 기간 안이어도 들어가지 않는다
        adjust(999L, 12L, DAY.minusDays(9), "2032-03-10 09:00:00");
        // 캠페인 없는 조정은 대상이 없어 기록한 날에 들어간다
        adjust(-300L, null, null, "2032-03-11 09:00:00");
        // 컬럼을 두기 전에 기록된 캠페인 조정도 대상이 없어 기록한 날에 들어간다
        adjust(70L, 12L, null, "2032-03-09 09:00:00");

        assertThat(item(DAY.toString())).isEqualTo(
                new AdminPointSummaryItem(DAY.toString(), 0, -1000, 0, 0, 0, 1000));
        assertThat(item(DAY.plusDays(1).toString())).isEqualTo(
                new AdminPointSummaryItem(DAY.plusDays(1).toString(), 0, 0, 0, 0, 0, -300));
        assertThat(item(DAY.minusDays(1).toString())).isEqualTo(
                new AdminPointSummaryItem(DAY.minusDays(1).toString(), 0, -70, 0, 0, 0, 70));
    }

    @Test
    void 소진은_DEDUCT를_기록한_날이_아니라_서빙_영업일에_들어간다() {
        // DAY 의 노출이 다음 날 00:30 에 기록됐다. 기록일에는 예약도 해제도 없다
        byte[] serveId = ServeIds.toBytes(UUID.randomUUID());
        jdbc.update("""
                insert into deduction_result
                    (serve_id, campaign_id, owner_id, business_date, billing_status, unit_price, created_at, updated_at)
                values (?, 12, ?, ?, 'BILLED', 700, '2032-03-11 00:30:00', '2032-03-11 00:30:00')
                """, serveId, OWNER, DAY);
        jdbc.update("""
                insert into point_ledger (owner_id, campaign_id, serve_id, entry_type, source, amount, created_at, updated_at)
                values (?, 12, ?, 'DEDUCT', 'REALTIME', -700, '2032-03-11 00:30:00', '2032-03-11 00:30:00')
                """, OWNER, serveId);

        assertThat(item(DAY.toString())).isEqualTo(new AdminPointSummaryItem(DAY.toString(), 0, 700, 0, 0, 0, 0));
        assertThat(item(DAY.plusDays(1).toString()))
                .isEqualTo(new AdminPointSummaryItem(DAY.plusDays(1).toString(), 0, 0, 0, 0, 0, 0));
    }
}
