package com.launchcatch.campaign.repository;

import com.launchcatch.campaign.entity.StoreAudience;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreAudienceRepository extends JpaRepository<StoreAudience, Long> {

    /*
     * 유일 키(store_id, target_radius, gender, age_group)로 upsert한다. id 생성 전략이
     * IDENTITY라 Hibernate가 entity 단위 INSERT를 배치로 못 묶는다(JPA save 대신 네이티브
     * 쿼리를 쓰는 이유). 호출마다 이 쿼리 하나씩 보내면 rewriteBatchedStatements=true가
     * 드라이버 레벨에서 묶어 보낸다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO store_audience
                (store_id, target_radius, gender, age_group, member_count, business_date, created_at, updated_at)
            VALUES
                (:storeId, :targetRadius, :gender, :ageGroup, :memberCount, :businessDate, :now, :now)
            ON DUPLICATE KEY UPDATE
                member_count = :memberCount,
                business_date = :businessDate,
                updated_at = :now
            """, nativeQuery = true)
    void upsert(
            @Param("storeId") long storeId,
            @Param("targetRadius") int targetRadius,
            @Param("gender") String gender,
            @Param("ageGroup") String ageGroup,
            @Param("memberCount") int memberCount,
            @Param("businessDate") LocalDate businessDate,
            @Param("now") LocalDateTime now);
}
