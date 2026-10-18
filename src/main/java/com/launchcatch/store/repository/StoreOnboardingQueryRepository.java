package com.launchcatch.store.repository;

import com.launchcatch.store.StoreOnboardingReadiness;
import com.launchcatch.store.dto.StoreOnboardingQueryRow;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreOnboardingQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // 한 SELECT에서 본인 업무 데이터와 버전을 같은 스냅샷으로 읽으며 진행 상태가 없어도 한 행을 반환한다.
    public StoreOnboardingQueryRow findByOwnerId(Long ownerId) {
        return jdbc.queryForObject("""
                select s.store_id, coalesce(p.version, 0) as version,
                    coalesce(s.finalized, false) as finalized,
                    coalesce(t.agreed = true and t.agreed_at is not null
                        and t.withdrawn_at is null, false) as terms_completed,
                    coalesce(length(trim(s.name)) > 0 and length(trim(s.category_code)) > 0
                        and length(trim(s.representative_name)) > 0
                        and length(trim(s.phone)) > 0 and length(trim(s.business_registration_number)) = 10
                        and s.business_verification_status = 'VERIFIED'
                        and s.business_verified_at is not null, false) as info_completed,
                    coalesce(length(trim(s.kakao_place_id)) > 0 and length(trim(s.road_address)) > 0
                        and s.latitude is not null and s.longitude is not null, false) as location_completed,
                    (select count(*) from store_business_hour h where h.store_id = s.store_id
                        and (h.closed = true or (h.open_time is not null and h.close_time is not null))) = 7
                        as hours_completed,
                    (select count(*) from store_image i where i.store_id = s.store_id
                        and i.image_type = 'LOGO' and i.upload_status = 'VERIFIED'
                        and length(trim(i.object_key)) > 0) = 1
                    and (select count(*) from store_image i where i.store_id = s.store_id
                        and i.image_type = 'INTERIOR') <= 3
                    and not exists(select 1 from store_image i where i.store_id = s.store_id
                        and i.image_type = 'INTERIOR'
                        and (i.upload_status <> 'VERIFIED' or length(trim(i.object_key)) = 0))
                        as images_completed,
                    (select count(*) from store_menu m where m.store_id = s.store_id) as menu_count,
                    (select count(*) from store_menu m where m.store_id = s.store_id) <= 3
                    and not exists(select 1 from store_menu m
                        left join store_menu_image i on i.store_menu_id = m.store_menu_id
                        where m.store_id = s.store_id and (length(trim(m.name)) = 0 or m.price < 0
                            or i.store_menu_image_id is null or i.upload_status <> 'VERIFIED'
                            or length(trim(i.object_key)) = 0)) as valid_menus
                from (select :ownerId as owner_id) subject
                left join store s on s.owner_id = subject.owner_id
                left join store_registration_progress p on p.owner_id = subject.owner_id
                left join owner_terms_agreement t on t.owner_id = subject.owner_id
                """, Map.of("ownerId", ownerId), (rs, rowNum) -> new StoreOnboardingQueryRow(
                        rs.getObject("store_id", Long.class), rs.getLong("version"), rs.getBoolean("finalized"),
                        new StoreOnboardingReadiness(rs.getBoolean("terms_completed"),
                                rs.getBoolean("info_completed"), rs.getBoolean("location_completed"),
                                rs.getBoolean("hours_completed"), rs.getBoolean("images_completed"),
                                rs.getInt("menu_count"), rs.getBoolean("valid_menus"))));
    }
}