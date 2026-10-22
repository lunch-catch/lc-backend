package com.launchcatch.store.repository;

import com.launchcatch.store.repository.StoreDetailData.Image;
import com.launchcatch.store.repository.StoreDetailData.Campaign;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreDetailRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public Optional<StoreDetailData> findFinalized(long storeId) {
        return jdbc.query("""
                select store_id, name, category_code, road_address, phone, latitude, longitude
                from store where store_id = :storeId and finalized = true
                """, Map.of("storeId", storeId), (rs, row) -> new StoreDetailData(
                        rs.getLong("store_id"), rs.getString("name"), rs.getString("category_code"),
                        rs.getString("road_address"), rs.getString("phone"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"))).stream().findFirst();
    }

    public List<Image> findImages(long storeId, String imageType) {
        return jdbc.query("""
                select object_key, sort_order from store_image
                where store_id = :storeId and image_type = :imageType and upload_status = 'VERIFIED'
                order by sort_order, store_image_id
                """, Map.of("storeId", storeId, "imageType", imageType), (rs, row) ->
                new Image(rs.getString("object_key"), rs.getObject("sort_order", Integer.class)));
    }

    public Optional<Campaign> findActiveCampaign(long storeId, LocalDate businessDate) {
        return jdbc.query("""
                select s.campaign_id, s.discount_target_type, s.discount_type, s.discount_value,
                    s.usable_start_time, s.usable_end_time, m.store_menu_id, m.name, m.price
                from store_campaign_summary s left join store_menu m
                    on m.store_menu_id = s.target_menu_id and m.store_id = s.store_id
                where s.store_id = :storeId and s.business_date = :businessDate and s.campaign_status = 'ACTIVE'
                """, Map.of("storeId", storeId, "businessDate", businessDate), (rs, row) -> {
                    return new Campaign(rs.getLong("campaign_id"),
                            StoreDiscountTargetType.valueOf(rs.getString("discount_target_type")),
                            StoreDiscountType.valueOf(rs.getString("discount_type")), rs.getInt("discount_value"),
                            rs.getObject("store_menu_id", Long.class), rs.getString("name"),
                            rs.getObject("price", Long.class), rs.getObject("usable_start_time", LocalTime.class),
                            rs.getObject("usable_end_time", LocalTime.class));
                }).stream().findFirst();
    }
}
