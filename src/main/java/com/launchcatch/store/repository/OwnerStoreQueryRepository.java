package com.launchcatch.store.repository;

import com.launchcatch.store.contract.OwnerStoreOverview.TermsAgreement;
import com.launchcatch.store.contract.OwnerStoreSnapshot.BusinessHour;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Image;
import com.launchcatch.store.contract.OwnerStoreSnapshot.Menu;
import com.launchcatch.store.contract.OwnerStoreSnapshot;
import com.launchcatch.store.dto.StoreRegistrationProgressSnapshot;
import com.launchcatch.store.entity.StoreRegistrationStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OwnerStoreQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public Optional<OwnerStoreSnapshot> findByOwnerId(Long ownerId) {
        return jdbc.query("""
                select store_id, finalized, name, category_code, representative_name,
                    business_registration_number, phone, business_verification_status,
                    business_verified_at, kakao_place_id, road_address, latitude, longitude
                from store where owner_id = :ownerId
                """, Map.of("ownerId", ownerId), (rs, row) -> new OwnerStoreSnapshot(
                        rs.getLong("store_id"), rs.getBoolean("finalized"), rs.getString("name"),
                        rs.getString("category_code"), rs.getString("representative_name"),
                        rs.getString("business_registration_number"), rs.getString("phone"),
                        rs.getString("business_verification_status"),
                        rs.getObject("business_verified_at", java.time.LocalDateTime.class),
                        rs.getString("kakao_place_id"), rs.getString("road_address"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"),
                        List.of(), null, List.of(), List.of())).stream().findFirst();
    }

    public List<BusinessHour> findBusinessHours(Long storeId) {
        return jdbc.query("""
                select day_of_week, open_time, close_time, closed, break_start_time, break_end_time, last_order_time
                from store_business_hour where store_id = :storeId
                order by field(day_of_week, 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')
                """, Map.of("storeId", storeId), (rs, row) -> new BusinessHour(
                        rs.getString("day_of_week"), rs.getObject("open_time", java.time.LocalTime.class),
                        rs.getObject("close_time", java.time.LocalTime.class), rs.getBoolean("closed"),
                        rs.getObject("break_start_time", java.time.LocalTime.class),
                        rs.getObject("break_end_time", java.time.LocalTime.class),
                        rs.getObject("last_order_time", java.time.LocalTime.class)));
    }

    public List<Image> findImages(Long storeId) {
        return jdbc.query("""
                select object_key, sort_order from store_image
                where store_id = :storeId and upload_status = 'VERIFIED'
                order by case image_type when 'LOGO' then 0 else 1 end, sort_order, store_image_id
                """, Map.of("storeId", storeId), (rs, row) -> image(rs));
    }

    public List<Menu> findMenus(Long storeId) {
        return jdbc.query("""
                select m.store_menu_id, m.name, m.price, m.description, m.sort_order, i.object_key
                from store_menu m left join store_menu_image i
                    on i.store_menu_id = m.store_menu_id and i.upload_status = 'VERIFIED'
                where m.store_id = :storeId order by m.sort_order, m.store_menu_id
                """, Map.of("storeId", storeId), (rs, row) -> new Menu(
                        rs.getLong("store_menu_id"), rs.getString("name"), rs.getLong("price"),
                        rs.getString("description"), rs.getInt("sort_order"),
                        rs.getString("object_key") == null ? null : new Image(rs.getString("object_key"), null, null)));
    }

    private static Image image(ResultSet rs) throws SQLException {
        return new Image(rs.getString("object_key"), null, rs.getObject("sort_order", Integer.class));
    }

    public Optional<StoreRegistrationProgressSnapshot> findProgress(Long ownerId) {
        return jdbc.query("""
                select store_id, resume_step, version from store_registration_progress where owner_id = :ownerId
                """, Map.of("ownerId", ownerId), (rs, row) -> new StoreRegistrationProgressSnapshot(
                        rs.getObject("store_id", Long.class), StoreRegistrationStep.valueOf(rs.getString("resume_step")),
                        rs.getLong("version"))).stream().findFirst();
    }

    public Optional<TermsAgreement> findTermsAgreement(Long ownerId) {
        return jdbc.query("""
                select agreed, agreed_at, created_at, withdrawn_at
                from owner_terms_agreement where owner_id = :ownerId
                """, Map.of("ownerId", ownerId), (rs, row) -> new TermsAgreement(
                        null, null, true, rs.getBoolean("agreed"),
                        rs.getObject("agreed_at", LocalDateTime.class),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("withdrawn_at", LocalDateTime.class))).stream().findFirst();
    }
}
