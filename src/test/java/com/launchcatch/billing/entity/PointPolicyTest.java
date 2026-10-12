package com.launchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 두 값이 서로를 부정하지 않게 막는다.
 *
 * 최소 충전 금액과 충전 상품은 함께 쓰인다. 상품 목록에 최소 금액보다 작은 값이 있으면,
 * 점주는 화면이 보여 준 금액을 골라 놓고 최소 충전 금액 미만으로 거절된다.
 */
class PointPolicyTest {

    private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 10);
    private static final long MIN_CHARGE = 10000L;
    private static final Long ADMIN_ID = 2L;

    private static List<Long> products() {
        return new ArrayList<>(List.of(10000L, 30000L, 50000L, 100000L));
    }

    private static PointPolicy policy(long minChargeAmount, List<Long> chargeProducts) {
        return PointPolicy.effectiveFrom(TOMORROW, minChargeAmount, chargeProducts, "조정", ADMIN_ID);
    }

    @Test
    @DisplayName("정책은 적용일과 최소 충전 금액, 상품 목록을 갖는다")
    void 생성() {
        PointPolicy policy = policy(MIN_CHARGE, products());

        assertThat(policy.getEffectiveDate()).isEqualTo(TOMORROW);
        assertThat(policy.getMinChargeAmount()).isEqualTo(MIN_CHARGE);
        assertThat(policy.getChargeProducts()).containsExactly(10000L, 30000L, 50000L, 100000L);
        assertThat(policy.getChangeReason()).isEqualTo("조정");
        assertThat(policy.getCreatedBy()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("최소 충전 금액보다 작은 충전 상품은 받지 않는다")
    void 상품_하한() {
        List<Long> tooSmall = new ArrayList<>(List.of(5000L, 30000L));

        assertThatThrownBy(() -> policy(MIN_CHARGE, tooSmall))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("충전 상품이 비어 있으면 받지 않는다")
    void 상품_비어있음() {
        assertThatThrownBy(() -> policy(MIN_CHARGE, new ArrayList<>()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최소 충전 금액이 0 이하면 받지 않는다")
    void 최소금액_검증() {
        assertThatThrownBy(() -> policy(0, products()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("적용일이 없으면 받지 않는다")
    void 적용일_필수() {
        assertThatThrownBy(() ->
                PointPolicy.effectiveFrom(null, MIN_CHARGE, products(), null, ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 마이그레이션이 넣는 최초 정책 행은 사람이 만든 것이 아니라 관리자 ID 가 없다
    @Test
    @DisplayName("관리자 ID가 없는 정책도 만들 수 있다")
    void 시스템_정책() {
        PointPolicy policy =
                PointPolicy.effectiveFrom(TOMORROW, MIN_CHARGE, products(), "최초 정책", null);

        assertThat(policy.getCreatedBy()).isNull();
    }

    /*
     * 목록을 받은 그대로 들고 있으면 호출한 쪽이 나중에 그 목록을 바꿀 때 엔티티의 값도 바뀐다.
     * 저장 전에 바뀌면 검증을 통과한 값과 저장되는 값이 달라진다.
     */
    @Test
    @DisplayName("넘겨받은 상품 목록을 복사해서 보관한다")
    void 상품_목록_복사() {
        List<Long> given = products();
        PointPolicy policy = policy(MIN_CHARGE, given);

        given.add(1L);

        assertThat(policy.getChargeProducts()).hasSize(4);
    }
}
