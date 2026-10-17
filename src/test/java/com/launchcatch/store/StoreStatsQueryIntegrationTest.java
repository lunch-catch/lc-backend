package com.launchcatch.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.store.contract.FinalizedStoreCounts;
import com.launchcatch.store.contract.StoreStatsQueryService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@ResourceLock("java.util.TimeZone.default")
class StoreStatsQueryIntegrationTest extends AbstractStoreIntegrationTest {
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 15);
    private static final AtomicLong OWNER_IDS = new AtomicLong(940000000);
    @Autowired StoreStatsQueryService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void 기존_가게는_집계에서_제외한다() {
        jdbc.update("update store set finalized = false, finalized_at = null");
    }

    @Test
    void KST_당일_등록만_신규로_세고_다음날_등록은_누적에서도_제외한다() {
        createFinalizedStore("2026-10-14T23:59:59.999999");
        createFinalizedStore("2026-10-15T00:00:00");
        createFinalizedStore("2026-10-15T08:59:59.999999");
        createFinalizedStore("2026-10-15T23:59:59.999999");
        createFinalizedStore("2026-10-16T00:00:00");
        createDraftStore();

        assertThat(service.finalizedStoreCounts(BUSINESS_DATE)).isEqualTo(new FinalizedStoreCounts(3, 4));
    }

    @Test
    void 과거_영업일의_누적은_그날_말을_기준으로_센다() {
        createFinalizedStore("2026-10-13T12:00:00");
        createFinalizedStore("2026-10-14T12:00:00");
        createFinalizedStore("2026-10-15T12:00:00");

        assertThat(service.finalizedStoreCounts(BUSINESS_DATE.minusDays(1)))
                .isEqualTo(new FinalizedStoreCounts(1, 2));
    }

    @Test
    void 최종_등록된_가게가_없으면_두_수치가_0이다() {
        createDraftStore();

        assertThat(service.finalizedStoreCounts(BUSINESS_DATE)).isEqualTo(new FinalizedStoreCounts(0, 0));
    }

    @Test
    void 신규가_없어도_이전_등록은_누적에_남는다() {
        createFinalizedStore("2026-10-14T12:00:00");

        assertThat(service.finalizedStoreCounts(BUSINESS_DATE)).isEqualTo(new FinalizedStoreCounts(0, 1));
    }

    @Test
    void 점주_계정_조회_없이_최종_등록_가게를_센다() {
        createFinalizedStore("2026-10-15T12:00:00");

        assertThat(service.finalizedStoreCounts(BUSINESS_DATE)).isEqualTo(new FinalizedStoreCounts(1, 1));
    }

    @Test
    void 서버_기본_시간대가_UTC여도_KST_자정으로_집계한다() {
        createFinalizedStore("2026-10-14T23:59:59.999999");
        createFinalizedStore("2026-10-15T00:00:00");
        createFinalizedStore("2026-10-15T23:59:59.999999");
        createFinalizedStore("2026-10-16T00:00:00");
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            assertThat(service.finalizedStoreCounts(BUSINESS_DATE)).isEqualTo(new FinalizedStoreCounts(2, 3));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private long createDraftStore() {
        long ownerId = OWNER_IDS.incrementAndGet();
        jdbc.update("""
                insert into store (owner_id, name, category_code, representative_name, created_at, updated_at)
                values (?, '집계 테스트 가게', 'KOREAN', '대표자', '2026-10-01 12:00:00', '2026-10-01 12:00:00')
                """, ownerId);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, ownerId);
    }

    private void createFinalizedStore(String finalizedAt) {
        long storeId = createDraftStore();
        jdbc.update("""
                update store set finalized = true, finalized_at = ?, business_verification_status = 'VERIFIED',
                    business_verified_at = '2026-10-01 12:00:00', latitude = 37.1234567, longitude = 127.1234567
                where store_id = ?
                """, LocalDateTime.parse(finalizedAt), storeId);
    }
}
