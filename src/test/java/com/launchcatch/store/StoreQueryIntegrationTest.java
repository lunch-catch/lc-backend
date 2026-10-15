package com.launchcatch.store;

import static org.assertj.core.api.Assertions.*;

import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.repository.StoreQueryRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private Long menuId;
    private Long otherMenuId;

    @BeforeEach
    void setUp() {
        ownerId = OWNER_IDS.addAndGet(2);
        storeId = createStore(ownerId, "첫 가게");
        otherStoreId = createStore(ownerId + 1, "다른 가게");
        menuId = createMenu(storeId, "국밥", 9000L);
        otherMenuId = createMenu(otherStoreId, "덮밥", 10000L);
    }

    @Test
    void 가게는_누락_ID를_제외하고_중복없이_좌표가_없어도_반환한다() {
        var result = service.findAllById(List.of(storeId, otherStoreId, storeId, Long.MAX_VALUE));

        assertThat(result).extracting("storeId").containsExactlyInAnyOrder(storeId, otherStoreId);
        assertThat(result).allSatisfy(store -> {
            assertThat(store.storeImageUrl()).isNull();
            assertThat(store.latitude()).isNull();
            assertThat(store.longitude()).isNull();
        });
        assertThat(service.findAllById(List.of(Long.MAX_VALUE))).isEmpty();
    }

    @Test
    void 메뉴는_소속_가게와_원_단위_가격을_반환하고_누락_ID를_제외한다() {
        var result = service.findMenusByIdIn(List.of(menuId, otherMenuId, menuId, Long.MAX_VALUE));

        assertThat(result).extracting("menuId").containsExactlyInAnyOrder(menuId, otherMenuId);
        assertThat(result).filteredOn(menu -> menu.menuId().equals(menuId)).singleElement().satisfies(menu -> {
            assertThat(menu.storeId()).isEqualTo(storeId);
            assertThat(menu.menuName()).isEqualTo("국밥");
            assertThat(menu.price()).isEqualTo(9000L);
            assertThat(menu.menuImageUrl()).isNull();
        });
        assertThat(service.findMenusByIdIn(List.of(Long.MAX_VALUE))).isEmpty();
    }

    @Test
    void 현재_소유_관계만_확인하고_미존재와_타인_소유는_false다() {
        assertThat(service.canManageStore(ownerId, storeId)).isTrue();
        assertThat(service.canManageStore(ownerId, otherStoreId)).isFalse();
        assertThat(service.canManageStore(ownerId, Long.MAX_VALUE)).isFalse();

        jdbc.update("update store set owner_id = ? where store_id = ?", ownerId + 2, storeId);

        assertThat(service.canManageStore(ownerId, storeId)).isFalse();
        assertThat(service.canManageStore(ownerId + 2, storeId)).isTrue();
    }

    @Test
    void 현재_좌표를_정밀도_손실없이_반환한다() {
        jdbc.update("update store set latitude = ?, longitude = ? where store_id = ?",
                new BigDecimal("37.1234567"), new BigDecimal("127.1234567"), storeId);

        assertThat(service.findAllById(List.of(storeId))).singleElement().satisfies(store -> {
            assertThat(store.latitude()).isEqualByComparingTo("37.1234567");
            assertThat(store.longitude()).isEqualByComparingTo("127.1234567");
        });
    }

    @Test
    void 검증된_LOGO만_가게에_결합하고_내부_이미지로_결과를_늘리지_않는다() {
        insertStoreImage("LOGO", "PENDING", "logo.png", null);
        insertStoreImage("INTERIOR", "VERIFIED", "interior.png", 1);
        assertThat(repository.findAllById(List.of(storeId))).singleElement()
                .satisfies(row -> assertThat(row.imageObjectKey()).isNull());

        jdbc.update("update store_image set upload_status = 'VERIFIED', verified_at = '2026-10-15 12:00:00' "
                + "where store_id = ? and image_type = 'LOGO'", storeId);

        assertThat(repository.findAllById(List.of(storeId))).singleElement()
                .satisfies(row -> assertThat(row.imageObjectKey()).isEqualTo("logo.png"));
    }

    @Test
    void 검증된_메뉴_이미지만_반환한다() {
        jdbc.update("""
                insert into store_menu_image (store_menu_id, object_key, upload_status, created_at, updated_at)
                values (?, 'menu.png', 'UPLOADED', '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, menuId);
        assertThat(repository.findMenusByIdIn(List.of(menuId))).singleElement()
                .satisfies(row -> assertThat(row.imageObjectKey()).isNull());

        jdbc.update("update store_menu_image set upload_status = 'VERIFIED', verified_at = '2026-10-15 12:00:00' "
                + "where store_menu_id = ?", menuId);

        assertThat(repository.findMenusByIdIn(List.of(menuId))).singleElement()
                .satisfies(row -> assertThat(row.imageObjectKey()).isEqualTo("menu.png"));
    }

    private Long createStore(long id, String name) {
        jdbc.update("""
                insert into store (owner_id, name, category_code, representative_name, created_at, updated_at)
                values (?, ?, 'KOREAN', '대표자', '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, id, name);
        return jdbc.queryForObject("select store_id from store where owner_id = ?", Long.class, id);
    }

    private Long createMenu(Long id, String name, long price) {
        jdbc.update("""
                insert into store_menu (store_id, name, price, sort_order, created_at, updated_at)
                values (?, ?, ?, 1, '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, id, name, price);
        return jdbc.queryForObject("select store_menu_id from store_menu where store_id = ?", Long.class, id);
    }

    private void insertStoreImage(String type, String status, String key, Integer order) {
        jdbc.update("""
                insert into store_image (store_id, image_type, object_key, sort_order, upload_status,
                    verified_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, '2026-10-15 12:00:00', '2026-10-15 12:00:00', '2026-10-15 12:00:00')
                """, storeId, type, key, order, status);
    }
}