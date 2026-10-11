package com.launchcatch.billing.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 점주의 포인트 계정이다. 점주마다 한 행이고 잔액은 담지 않는다.
 *
 * 잔액을 읽고 판단한 뒤 원장에 쓰는 작업이 넷 있다. 환불 승인, 음수 조정, 00:00 예약,
 * 충전 직후 재예약이다. 넷 모두 읽기와 쓰기 사이에 다른 요청이 끼면 갱신 손실이 된다.
 * 그 구간을 점주 단위로 직렬화하려면 잠글 행이 필요한데, 정산은 점주 테이블을 잠글 수 없다.
 * 도메인 경계를 넘는 참조가 금지돼 있어서다. 그래서 이 행이 그 기준이 된다.
 *
 * 잔액 컬럼을 두지 않는 것이 요점이다. 잔액의 유일한 출처는 원장의 합이고, 여기에 숫자를
 * 두면 두 값이 갈릴 자리가 생긴다. 이 행은 잠금의 대상일 뿐이다.
 *
 * 충전은 잔액을 늘리기만 하므로 이 잠금이 필요 없다.
 */
@Entity
@Table(name = "point_account")
@AttributeOverride(name = "id", column = @Column(name = "point_account_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointAccount extends BaseTimeEntity {

    @Column(name = "owner_id", nullable = false, unique = true, updatable = false)
    private Long ownerId;

    private PointAccount(Long ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("점주 ID는 필수입니다.");
        }
        this.ownerId = ownerId;
    }

    public static PointAccount forOwner(Long ownerId) {
        return new PointAccount(ownerId);
    }
}
