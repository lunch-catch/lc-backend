package com.launchcatch.store.repository;

import com.launchcatch.store.dto.StoreMenuQueryRow;
import com.launchcatch.store.dto.StoreQueryRow;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // 이미지는 검증된 대표 이미지(LOGO)만 연결하며 이미지가 없어도 LEFT JOIN으로 가게를 반환한다.
    public List<StoreQueryRow> findAllById(List<Long> storeIds) {
        return jdbc.query("""
                select s.store_id, s.name, i.object_key, s.latitude, s.longitude
                from store s
                left join store_image i on i.store_id = s.store_id
                    and i.image_type = 'LOGO' and i.upload_status = 'VERIFIED'
                where s.store_id in (:ids)
                """, Map.of("ids", storeIds), (rs, rowNum) -> new StoreQueryRow(
                        rs.getLong("store_id"), rs.getString("name"), rs.getString("object_key"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude")));
    }

    // 쿠폰의 과거 점주 정보 대신 현재 store.owner_id로 확인하며 가게 미존재와 타인 소유는 모두 false가 된다.
    public boolean canManageStore(Long ownerId, Long storeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from store where store_id = :storeId and owner_id = :ownerId)
                """, Map.of("ownerId", ownerId, "storeId", storeId), Boolean.class));
    }

    // IN 조건으로 메뉴를 한 번에 조회하며 검증된 이미지가 없는 메뉴도 결과에 남긴다.
    public List<StoreMenuQueryRow> findMenusByIdIn(List<Long> menuIds) {
        return jdbc.query("""
                select m.store_menu_id, m.store_id, i.object_key, m.name, m.price
                from store_menu m
                left join store_menu_image i on i.store_menu_id = m.store_menu_id
                    and i.upload_status = 'VERIFIED'
                where m.store_menu_id in (:ids)
                """, Map.of("ids", menuIds), (rs, rowNum) -> new StoreMenuQueryRow(
                        rs.getLong("store_menu_id"), rs.getLong("store_id"), rs.getString("object_key"),
                        rs.getString("name"), rs.getLong("price")));
    }
}