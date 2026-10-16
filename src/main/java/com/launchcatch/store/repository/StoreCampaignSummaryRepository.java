package com.launchcatch.store.repository;

import com.launchcatch.store.contract.StoreCampaignSummaryCommand;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StoreCampaignSummaryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public boolean menuBelongsToStore(long storeId, long menuId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from store_menu where store_id = :storeId and store_menu_id = :menuId)
                """, Map.of("storeId", storeId, "menuId", menuId), Boolean.class));
    }

    public void upsert(long storeId, long campaignId, StoreCampaignSummaryCommand command, LocalDateTime now) {
        // 단건과 일괄 저장이 같은 중복 키 처리와 생성 시각 보존 규칙을 사용한다.
        upsertAll(List.of(new StoreCampaignSummaryUpdate(storeId, campaignId, command)), now);
    }

    // 반환 Map의 키는 메뉴 ID이고 값은 해당 메뉴를 소유한 가게 ID다.
    public Map<Long, Long> findMenuStoreIds(List<Long> menuIds) {
        return jdbc.query("""
                select store_menu_id, store_id from store_menu where store_menu_id in (:menuIds)
                """, Map.of("menuIds", menuIds), rs -> {
            Map<Long, Long> menuStoreIds = new HashMap<>();
            while (rs.next()) {
                menuStoreIds.put(rs.getLong("store_menu_id"), rs.getLong("store_id"));
            }
            return menuStoreIds;
        });
    }

    public void upsertAll(List<StoreCampaignSummaryUpdate> updates, LocalDateTime now) {
        // 배열 값은 아래 INSERT의 컬럼 순서와 같으며 마지막 두 값은 생성 시각과 수정 시각이다.
        List<Object[]> rows = updates.stream().map(update -> {
            StoreCampaignSummaryCommand command = update.command();
            return new Object[]{update.storeId(), update.campaignId(), command.businessDate(),
                    command.campaignStatus().name(), command.discountTargetType().name(), command.discountType().name(),
                    command.discountValue(), command.targetMenuId(), command.usableStartTime(), command.usableEndTime(),
                    now, now};
        }).toList();
        /*
         * NamedParameterJdbcTemplate이 Object[] 목록을 (?, ?, ...), (?, ?, ...) 형태로 펼쳐 한 SQL로 실행한다.
         * incoming은 이번에 전달한 새 행의 별칭이다.
         * 같은 business_date와 campaign_id의 행이 있으면 새 값으로 갱신하며 기존 created_at은 보존한다.
         */
        jdbc.update("""
                insert into store_campaign_summary(store_id, campaign_id, business_date, campaign_status,
                    discount_target_type, discount_type, discount_value, target_menu_id,
                    usable_start_time, usable_end_time, created_at, updated_at)
                values :rows as incoming
                on duplicate key update
                    store_id = incoming.store_id, campaign_status = incoming.campaign_status,
                    discount_target_type = incoming.discount_target_type, discount_type = incoming.discount_type,
                    discount_value = incoming.discount_value, target_menu_id = incoming.target_menu_id,
                    usable_start_time = incoming.usable_start_time, usable_end_time = incoming.usable_end_time,
                    updated_at = incoming.updated_at
                """, Map.of("rows", rows));
    }
}
