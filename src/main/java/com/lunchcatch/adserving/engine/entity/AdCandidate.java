package com.lunchcatch.adserving.engine.entity;

import com.lunchcatch.adserving.engine.FeedCandidate;
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
import java.util.List;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 그날의 피드 후보 스냅샷이다. 서빙 경로는 이 테이블만 읽는다(FR-093).
 * 행은 00:00 적재 배치가 만들고 이 엔티티는 아직 읽기만 한다. 그래서 생성 경로를 두지 않았고 피드와 찜 목록이 쓰는 컬럼만 매핑한다.
 * 키가 (영업일, 캠페인) 복합키라 BaseTimeEntity 의 단일 식별자를 상속할 수 없다.
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

    @Column(name = "store_name", nullable = false)
    private String storeName;

    /*
     * 가게 대표 이미지(LOGO) 주소다.
     * 가게가 올리지 않았으면 null 이다.
     * 찜 목록이 표시한다.
     */
    @Column(name = "store_image_url")
    private String storeImageUrl;

    @Column(name = "category_code", nullable = false)
    private String categoryCode;

    @Column(name = "latitude", nullable = false)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false)
    private BigDecimal longitude;

    @Column(name = "target_radius", nullable = false)
    private short targetRadius;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_gender", nullable = false)
    private TargetGender targetGender;

    // AGE_20S,AGE_30S 처럼 콤마로 이은 값이다. 캠페인의 같은 컬럼을 그대로 복사한다.
    @Column(name = "target_age_groups", nullable = false)
    private String targetAgeGroups;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_target_type", nullable = false)
    private DiscountTarget discountTarget;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private int discountValue;

    /*
     * 할인 대상 메뉴명이다.
     * MENU 일 때만 값이 있다(chk_candidate_discount_menu).
     * 찜 목록이 표시한다.
     */
    @Column(name = "discount_menu_name")
    private String discountMenuName;

    @Column(name = "usable_start_time", nullable = false)
    private LocalTime usableStartTime;

    @Column(name = "usable_end_time", nullable = false)
    private LocalTime usableEndTime;

    @Column(name = "issue_quantity", nullable = false)
    private int issueQuantity;

    // 배분 슬롯에서 진행률이 같을 때 먼저 만든 캠페인을 앞에 둔다.
    @Column(name = "campaign_created_at", nullable = false)
    private LocalDateTime campaignCreatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_state", nullable = false)
    private CandidateCampaignState campaignState;

    @Column(name = "sold_out_at")
    private LocalDateTime soldOutAt;

    // 신규 우선 노출의 마지막 날이고 null 이면 대상이 아니다(FR-093)
    @Column(name = "new_priority_until")
    private LocalDate newPriorityUntil;

    /*
     * 정산의 그날 예약액이다(#837).
     * null 이면 아직 읽지 못한 것이고 0 이면 그날 계획이 없는 것이다.
     */
    @Column(name = "reserved_amount")
    private Long reservedAmount;

    @Column(name = "unit_price")
    private Long unitPrice;

    public Long getCampaignId() {
        return id.getCampaignId();
    }

    public FeedCandidate toFeedCandidate() {
        return new FeedCandidate(getCampaignId(), storeId, storeName, categoryCode,
                latitude.doubleValue(), longitude.doubleValue(), targetRadius, targetGender,
                Set.copyOf(List.of(targetAgeGroups.split(","))), discountTarget, discountType, discountValue,
                usableStartTime, usableEndTime, issueQuantity, campaignCreatedAt, newPriorityUntil);
    }
}
