package com.launchcatch.campaign.entity;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * store_audience 한 행이다. 유일 키(storeId, targetRadius, gender, ageGroup) 한 칸의
 * 최신 집계값만 들고 있다. 쓰기는 StoreAudienceUpdaterImpl의 다중 행 INSERT ... ON DUPLICATE
 * KEY UPDATE로만 일어나 이 엔티티를 거치지 않는다(가게 500곳이면 24,000행을 매번 SELECT로
 * 먼저 확인하는 비용을 피한다). 이 클래스는 테스트에서 결과를 읽어 확인하는 용도다.
 */
@Entity
@Table(name = "store_audience")
@AttributeOverride(name = "id", column = @Column(name = "store_audience_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreAudience extends BaseTimeEntity {

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "target_radius", nullable = false)
    private int targetRadius;

    @Convert(converter = StoreAudienceGenderConverter.class)
    @Column(nullable = false, length = 10)
    private StoreAudienceGender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_group", nullable = false, length = 20)
    private AgeGroup ageGroup;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;
}
