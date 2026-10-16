package com.launchcatch.store.repository;

import com.launchcatch.store.dto.StoreMenuQueryRow;
import com.launchcatch.store.dto.StoreQueryRow;
import com.launchcatch.store.dto.StoreSummaryQueryRow;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public List<Long> findOwnerIdsByNameContaining(String keyword, long fetchLimit) {
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        return jdbc.query("""
                select owner_id from store
                where name like :pattern escape '!'
                limit :fetchLimit
                """, Map.of("pattern", pattern, "fetchLimit", fetchLimit),
                (rs, rowNum) -> rs.getLong("owner_id"));
    }

    public List<StoreSummaryQueryRow> findSummariesByOwnerIds(Collection<Long> ownerIds) {
        return jdbc.query("""
                select owner_id, store_id, name, finalized, business_registration_number
                from store where owner_id in (:ownerIds)
                """, Map.of("ownerIds", ownerIds), (rs, rowNum) -> new StoreSummaryQueryRow(
                        rs.getLong("owner_id"), rs.getLong("store_id"), rs.getString("name"),
                        rs.getBoolean("finalized"), rs.getString("business_registration_number")));
    }

    // 최종 등록된 가게만 조회하며 LEFT JOIN으로 이미지 누락도 서비스에서 오류로 확인할 수 있게 한다.
    public List<StoreQueryRow> findAllById(List<Long> storeIds) {
        return jdbc.query("""
                select s.store_id, s.name, i.object_key, s.latitude, s.longitude
                from store s
                left join store_image i on i.store_id = s.store_id
                    and i.image_type = 'LOGO' and i.upload_status = 'VERIFIED'
                where s.store_id in (:ids) and s.finalized = true
                """, Map.of("ids", storeIds), (rs, rowNum) -> new StoreQueryRow(
                        rs.getLong("store_id"), rs.getString("name"), rs.getString("object_key"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude")));
    }

    // 현재 store.owner_id가 일치하고 최종 등록된 가게만 허용하며 미존재, 타인 소유와 등록 중인 가게는 false가 된다.
    public boolean canManageStore(Long ownerId, Long storeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from store where store_id = :storeId and owner_id = :ownerId and finalized = true)
                """, Map.of("ownerId", ownerId, "storeId", storeId), Boolean.class));
    }

    // 최종 등록된 가게의 메뉴만 일괄 조회하며 검증된 이미지가 없는 경우도 서비스가 오류로 확인한다.
    public List<StoreMenuQueryRow> findMenusByIdIn(List<Long> menuIds) {
        return jdbc.query("""
                select m.store_menu_id, m.store_id, i.object_key, m.name, m.price
                from store_menu m
                join store s on s.store_id = m.store_id
                left join store_menu_image i on i.store_menu_id = m.store_menu_id
                    and i.upload_status = 'VERIFIED'
                where m.store_menu_id in (:ids) and s.finalized = true
                """, Map.of("ids", menuIds), (rs, rowNum) -> new StoreMenuQueryRow(
                        rs.getLong("store_menu_id"), rs.getLong("store_id"), rs.getString("object_key"),
                        rs.getString("name"), rs.getLong("price")));
    }
}