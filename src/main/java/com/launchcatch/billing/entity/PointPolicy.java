package com.launchcatch.billing.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/*
 * 포인트 정책이다. 최소 충전 금액과 충전 상품 목록만 갖는다.
 *
 * effective_date 가 UNIQUE 인 이력형 테이블이다. 과거 행은 고치지도 지우지도 않고 새 행을
 * 더한다. 그래서 모든 컬럼이 updatable = false 다. 관리자가 같은 날짜로 다시 저장하는 경우만
 * 그 행을 바꾸는데, 그것은 내일 적용분 한 행이라 서비스가 지우고 새로 넣는다.
 *
 * 노출 단가와 하루 예산 하한은 여기 없다. 2026-10-05 에 운영 도메인의 플랫폼 설정값으로
 * 넘겼다. 정산이 캠페인 설정값을 들고 있으면 의존이 순환한다.
 */
@Entity
@Table(name = "point_policy")
@AttributeOverride(name = "id", column = @Column(name = "point_policy_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointPolicy extends BaseTimeEntity {

    private static final int CHANGE_REASON_MAX = 255;

    @Column(name = "min_charge_amount", nullable = false, updatable = false)
    private long minChargeAmount;

    /*
     * 점주가 고를 수 있는 충전 금액 목록이다. JSON 배열로 저장한다.
     * chk_point_policy_charge_products 가 배열이고 비어 있지 않음을 DB 에서 강제한다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "charge_products", nullable = false, updatable = false)
    private List<Long> chargeProducts;

    @Column(name = "change_reason", length = CHANGE_REASON_MAX, updatable = false)
    private String changeReason;

    @Column(name = "effective_date", nullable = false, updatable = false)
    private LocalDate effectiveDate;

    /*
     * 이 정책을 저장한 관리자 ID 다. 소프트 참조라 외래 키가 없다.
     * 마이그레이션이 넣은 최초 정책 행만 NULL 이다. 사람이 만든 행이 아니기 때문이다.
     */
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    private PointPolicy(LocalDate effectiveDate, long minChargeAmount, List<Long> chargeProducts,
                        String changeReason, Long createdBy) {
        if (effectiveDate == null) {
            throw new IllegalArgumentException("적용일은 필수입니다.");
        }
        if (minChargeAmount <= 0) {
            throw new IllegalArgumentException("최소 충전 금액은 0보다 커야 합니다: " + minChargeAmount);
        }
        if (chargeProducts == null || chargeProducts.isEmpty()) {
            throw new IllegalArgumentException("충전 상품은 하나 이상이어야 합니다.");
        }
        /*
         * 충전 상품이 최소 충전 금액보다 작으면 두 값이 서로를 부정한다.
         * 그 상품을 고른 점주는 목록에 있는 금액으로 최소 충전 금액 미만을 받는다.
         */
        for (Long product : chargeProducts) {
            if (product == null || product < minChargeAmount) {
                throw new IllegalArgumentException(
                        "충전 상품은 최소 충전 금액 이상이어야 합니다: " + product);
            }
        }
        if (changeReason != null && changeReason.length() > CHANGE_REASON_MAX) {
            throw new IllegalArgumentException("변경 사유는 " + CHANGE_REASON_MAX + "자 이하여야 합니다.");
        }

        this.effectiveDate = effectiveDate;
        this.minChargeAmount = minChargeAmount;
        // 밖에서 넘긴 목록이 나중에 바뀌어도 이 엔티티의 값은 변하지 않아야 한다
        this.chargeProducts = List.copyOf(chargeProducts);
        this.changeReason = changeReason;
        this.createdBy = createdBy;
    }

    /*
     * 그 날짜부터 적용할 정책을 만든다.
     *
     * 적용일을 클라이언트가 정하지 않는다. 관리자 저장은 항상 내일이고 그 값은 서비스가
     * 서버 시계로 넣는다. 최초 정책 행은 마이그레이션이 과거 날짜로 직접 넣는다.
     */
    public static PointPolicy effectiveFrom(LocalDate effectiveDate, long minChargeAmount,
                                            List<Long> chargeProducts, String changeReason,
                                            Long createdBy) {
        return new PointPolicy(effectiveDate, minChargeAmount, chargeProducts, changeReason, createdBy);
    }
}
