package com.launchcatch.adserving.engine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 그날의 피드 후보 한 건이다. 서빙 경로는 이 테이블만 읽는다.
 *
 * 행을 채우는 것은 00:00 (8)단계(#180)라 이 엔티티에는 생성 메서드가 없고 읽기 매핑만 둔다.
 * 피드가 쓰지 않는 컬럼(가게 이미지, 할인 메뉴명, 상태 변경 시각, 신규 우선 노출)은 매핑하지 않는다.
 */
@Entity
@Table(name = "ad_candidate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdCandidate {

    @EmbeddedId
    private AdCandidateId id;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "store_name", nullable = false, length = 100)
    private String storeName;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Column(name = "latitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "target_radius", nullable = false)
    private Short targetRadius;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_gender", nullable = false, length = 10)
    private TargetGender targetGender;

    // 콤마로 이은 연령대 코드다. member.contract.AgeGroup 의 이름과 같다
    @Column(name = "target_age_groups", nullable = false, length = 50)
    private String targetAgeGroups;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_target_type", nullable = false, length = 20)
    private DiscountTarget discountTarget;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private int discountValue;

    @Column(name = "usable_start_time", nullable = false)
    private LocalTime usableStartTime;

    @Column(name = "usable_end_time", nullable = false)
    private LocalTime usableEndTime;

    @Column(name = "issue_quantity", nullable = false)
    private int issueQuantity;

    @Column(name = "campaign_created_at", nullable = false)
    private LocalDateTime campaignCreatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_state", nullable = false, length = 30)
    private CandidateCampaignState campaignState;

    @Column(name = "sold_out_at")
    private LocalDateTime soldOutAt;

    public Long getCampaignId() {
        return id.getCampaignId();
    }

    public LocalDate getBusinessDate() {
        return id.getBusinessDate();
    }
}
