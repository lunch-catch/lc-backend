package com.launchcatch.store.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import com.launchcatch.store.contract.StoreCampaignStatus;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

// 목록 조회에 필요한 컬럼만 매핑하며 요약의 생성과 갱신은 전용 JDBC Repository가 처리한다.
@Entity
@Immutable
@Table(name = "store_campaign_summary")
@AttributeOverride(name = "id", column = @Column(name = "store_campaign_summary_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreCampaignSummary extends BaseTimeEntity {
    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_status", nullable = false, length = 20)
    private StoreCampaignStatus campaignStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_target_type", nullable = false, length = 20)
    private StoreDiscountTargetType discountTargetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private StoreDiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private int discountValue;

    @Column(name = "target_menu_id")
    private Long targetMenuId;
}
