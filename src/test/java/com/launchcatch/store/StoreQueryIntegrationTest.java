package com.launchcatch.store;

import static org.assertj.core.api.Assertions.*;

import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.repository.StoreQueryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 각 테스트에서 넣거나 바꾼 데이터는 종료 시 롤백해 다른 테스트의 조회 결과에 영향을 주지 않는다.
@Transactional
class StoreQueryIntegrationTest extends AbstractStoreIntegrationTest {
    private static final AtomicLong OWNER_IDS = new AtomicLong(930000000);
    @Autowired StoreQueryService service;
    @Autowired StoreQueryRepository repository;
    @Autowired JdbcTemplate jdbc;
    private long ownerId;
    private Long storeId;
    private Long otherStoreId;
    private Long draftStoreId;
    private Long menuId;
    private Long otherMenuId;
    private Long draftMenuId;

    @BeforeEach
    void setUp() {
        ownerId = OWNER_IDS.addAndGet(3);
        storeId = createStore(ownerId, "첫 가게");
        otherStoreId = createStore(ownerId + 1, "다른 가게");
        draftStoreId = createStore(ownerId + 2, "온보딩 중인 가게");
        menuId = createMenu(storeId, "국밥", 9000L);
        otherMenuId = createMenu(otherStoreId, "덮밥", 10000L);
        draftMenuId = createMenu(draftStoreId, "등록 중인 메뉴", 8000L);
        finalizeStore(storeId);
        finalizeStore(otherStoreId);
        insertStoreImage(storeId, "LOGO", "stores/" + storeId + ".png", null);
        insertStoreImage(otherStoreId, "LOGO", "stores/" + otherStoreId + ".png", null);
        insertMenuImage(menuId);
        insertMenuImage(otherMenuId);
    }

    @Test
    void 점주_요약은_등록_전_가게와_마스킹한_번호를_포함하고_없는_점주는_제외한다() {
        jdbc.update("update store set business_registration_number = '1234567890' where store_id = ?", storeId);
        assertThat(service.findSummariesByOwnerIds(List.of(ownerId, ownerId + 2, ownerId, Long.MAX_VALUE)))
                .extracting("ownerId", "storeId", "finalized", "businessRegistrationNumber")
                .containsExactlyInAnyOrder(tuple(ownerId, storeId, true, "123**67890"),
                        tuple(ownerId + 2, draftStoreId, false, null));
        assertThat(service.findSummariesByOwnerIds(List.of())).isEmpty();
        assertThat(service.findSummariesByOwnerIds(List.of(Long.MAX_VALUE))).isEmpty();
    }

    @Test
    void 상호_부분_검색은_등록_전_가게도_포함하고_제한_초과를_거절한다() {
        String keyword = "상호검색" + ownerId;
        jdbc.update("update store set name = ? where store_id in (?, ?)", "앞" + keyword + "뒤", storeId, draftStoreId);
        assertThat(service.findOwnerIdsByNameContaining(keyword, 2)).containsExactlyInAnyOrder(ownerId, ownerId + 2);
        assertThatIllegalArgumentException().isThrownBy(() -> service.findOwnerIdsByNameContaining(keyword, 1));
        assertThat(service.findOwnerIdsByNameContaining(keyword + "없음", 1)).isEmpty();
    }

    @Test
    void 일만_건에서_백명_요약은_SQL_한번이며_조회는_일초_이내다() {
        long firstOwner = OWNER_IDS.addAndGet(10000);
        var owners = java.util.stream.LongStream.range(firstOwner, firstOwner + 10000).boxed().toList();
        String keyword = "성능검증" + firstOwner;
        jdbc.batchUpdate("""
                insert into store (owner_id, name, category_code, representative_name, created_at, updated_at)
                values (?, ?, 'KOREAN', '대표자', '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, owners, 500, (ps, id) -> {
                    ps.setLong(1, id);
                    ps.setString(2, keyword);
                });
        var page = owners.subList(0, 100);
        service.findSummariesByOwnerIds(page);
        service.findOwnerIdsByNameContaining(keyword, 10000);
        long selectsBefore = sessionSelects();
        long started = System.nanoTime();
        var summaries = service.findSummariesByOwnerIds(page);
        long summaryNanos = System.nanoTime() - started;
        assertThat(sessionSelects() - selectsBefore).isEqualTo(1);
        assertThat(summaries).hasSize(100);
        started = System.nanoTime();
        var matches = service.findOwnerIdsByNameContaining(keyword, 10000);
        long searchNanos = System.nanoTime() - started;
        assertThat(matches).containsExactlyInAnyOrderElementsOf(owners);
        assertThat(summaryNanos).isLessThan(java.time.Duration.ofSeconds(1).toNanos());
        assertThat(searchNanos).isLessThan(java.time.Duration.ofSeconds(1).toNanos());
        System.out.printf("store query 10k: summaries=%.3f ms, search=%.3f ms%n",
                summaryNanos / 1_000_000.0, searchNanos / 1_000_000.0);
    }

    private long sessionSelects() {
        return Long.parseLong((String) jdbc.queryForMap("show session status like 'Com_select'").get("Value"));
    }
    @Test
    void 최종_등록된_가게만_일괄_조회하고_누락_ID와_중복을_제외한다() {
        var result = service.findAllById(List.of(storeId, otherStoreId, draftStoreId, storeId, Long.MAX_VALUE));

        assertThat(result).extracting("storeId").containsExactlyInAnyOrder(storeId, otherStoreId);
        assertThat(result).allSatisfy(store -> {
            assertThat(store.storeImageUrl()).isNotBlank();
            assertThat(store.latitude()).isEqualTo(37.1234567);
            assertThat(store.longitude()).isEqualTo(127.1234567);
        });
        assertThat(service.findAllById(List.of(draftStoreId, Long.MAX_VALUE))).isEmpty();
    }

    @Test
    void 최종_등록된_가게의_메뉴만_조회하고_누락_ID와_중복을_제외한다() {
        var result = service.findMenusByIdIn(List.of(menuId, otherMenuId, draftMenuId, menuId, Long.MAX_VALUE));

        assertThat(result).extracting("menuId").containsExactlyInAnyOrder(menuId, otherMenuId);
        assertThat(result).filteredOn(menu -> menu.menuId().equals(menuId)).singleElement().satisfies(menu -> {
            assertThat(menu.storeId()).isEqualTo(storeId);
            assertThat(menu.menuName()).isEqualTo("국밥");
            assertThat(menu.price()).isEqualTo(9000L);
            assertThat(menu.menuImageUrl()).isNotBlank();
        });
        assertThat(service.findMenusByIdIn(List.of(draftMenuId, Long.MAX_VALUE))).isEmpty();
    }

    @Test
    void 최종_등록된_현재_본인_가게만_true이며_미존재와_타인_소유는_false다() {
        assertThat(service.canManageStore(ownerId, storeId)).isTrue();
        assertThat(service.canManageStore(ownerId, otherStoreId)).isFalse();
        assertThat(service.canManageStore(ownerId, Long.MAX_VALUE)).isFalse();

        jdbc.update("update store set owner_id = ? where store_id = ?", ownerId + 3, storeId);

        assertThat(service.canManageStore(ownerId, storeId)).isFalse();
        assertThat(service.canManageStore(ownerId + 3, storeId)).isTrue();
    }

    @Test
    void 본인_소유여도_등록_중인_가게는_false다() {
        assertThat(service.canManageStore(ownerId + 2, draftStoreId)).isFalse();
        assertThat(service.findAllById(List.of(draftStoreId))).isEmpty();
    }

    @Test
    void 현재_좌표를_Double로_반환한다() {
        jdbc.update("update store set latitude = ?, longitude = ? where store_id = ?",
                new BigDecimal("37.7654321"), new BigDecimal("127.7654321"), storeId);

        assertThat(service.findAllById(List.of(storeId))).singleElement().satisfies(store -> {
            assertThat(store.latitude()).isCloseTo(37.7654321, within(0.0000001));
            assertThat(store.longitude()).isCloseTo(127.7654321, within(0.0000001));
        });
    }

    @Test
    void 대표_이미지가_없으면_내부_이미지가_있어도_가게_조회는_실패한다() {
        jdbc.update("delete from store_image where store_id = ?", storeId);
        insertStoreImage(storeId, "INTERIOR", "interior/" + storeId + ".png", 1);

        assertThatThrownBy(() -> service.findAllById(List.of(storeId, otherStoreId)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미지가 누락");
    }

    @Test
    void 메뉴_이미지가_없으면_메뉴_조회는_실패한다() {
        jdbc.update("delete from store_menu_image where store_menu_id = ?", menuId);

        assertThatThrownBy(() -> service.findMenusByIdIn(List.of(menuId, otherMenuId)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미지가 누락");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "UPLOADED"})
    void 대표_이미지_검증이_완료되지_않았으면_가게_조회는_실패한다(String uploadStatus) {
        jdbc.update("update store_image set upload_status = ?, verified_at = null where store_id = ?",
                uploadStatus, storeId);

        assertThatThrownBy(() -> service.findAllById(List.of(storeId)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미지가 누락");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "UPLOADED"})
    void 메뉴_이미지_검증이_완료되지_않았으면_메뉴_조회는_실패한다(String uploadStatus) {
        jdbc.update("update store_menu_image set upload_status = ?, verified_at = null where store_menu_id = ?",
                uploadStatus, menuId);

        assertThatThrownBy(() -> service.findMenusByIdIn(List.of(menuId)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미지가 누락");
    }

    @Test
    void 검증된_이미지_저장_키를_조회하고_내부_이미지는_결과를_늘리지_않는다() {
        insertStoreImage(storeId, "INTERIOR", "interior/" + storeId + ".png", 1);

        assertThat(repository.findAllById(List.of(storeId))).singleElement()
                .satisfies(row -> assertThat(row.imageObjectKey()).isEqualTo("stores/" + storeId + ".png"));
        assertThat(repository.findMenusByIdIn(List.of(menuId))).singleElement()
                .satisfies(row -> assertThat(row.imageObjectKey()).isEqualTo("menus/" + menuId + ".png"));
    }

    private Long createStore(long id, String name) {
        jdbc.update("""
                insert into store (owner_id, name, category_code, representative_name, created_at, updated_at)
                values (?, ?, 'KOREAN', '대표자', '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, id, name);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, id);
    }

    private void finalizeStore(Long id) {
        jdbc.update("""
                update store set finalized = true, finalized_at = '2026-10-15 12:00:00',
                    business_verification_status = 'VERIFIED', business_verified_at = '2026-10-15 12:00:00',
                    latitude = 37.1234567, longitude = 127.1234567
                where store_id = ?
                """, id);
    }

    private Long createMenu(Long id, String name, long price) {
        jdbc.update("""
                insert into store_menu (store_id, name, price, sort_order, created_at, updated_at)
                values (?, ?, ?, 1, '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, id, name, price);
        return jdbc.queryForObject("select store_menu_id from store_menu where store_id = ?", Long.class, id);
    }

    private void insertStoreImage(Long id, String type, String key, Integer order) {
        jdbc.update("""
                insert into store_image (store_id, image_type, object_key, sort_order, upload_status,
                    verified_at, created_at, updated_at)
                values (?, ?, ?, ?, 'VERIFIED', '2026-10-15 12:00:00', '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, id, type, key, order);
    }

    private void insertMenuImage(Long id) {
        jdbc.update("""
                insert into store_menu_image (store_menu_id, object_key, upload_status,
                    verified_at, created_at, updated_at)
                values (?, ?, 'VERIFIED', '2026-10-15 12:00:00', '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, id, "menus/" + id + ".png");
    }
}