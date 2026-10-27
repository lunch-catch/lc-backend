package com.lunchcatch.billing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * 부호 규칙을 고정한다.
 *
 * 원장은 부호가 있는 값을 저장하고 잔액은 그 합이다. V51 의 계산식은 크기로 적혀 있어서
 * 그대로 옮기면 부호가 뒤집히는데, 그 실수는 잔액이 음수로 나올 때까지 드러나지 않는다.
 * 그래서 팩터리가 양수를 받아 부호를 정하는 것을 테스트로 묶는다.
 */
class PointLedgerTest {

    private static final Long OWNER_ID = 7L;
    private static final LocalDate TARGET = LocalDate.of(2026, 10, 13);
    private static final Long PAYMENT_ID = 42L;
    private static final long AMOUNT = 10000L;

    @Test
    @DisplayName("충전은 양수로 저장한다")
    void 충전_부호() {
        PointLedger charge = PointLedger.charge(OWNER_ID, PAYMENT_ID, AMOUNT, LedgerSource.REALTIME);

        assertThat(charge.getAmount()).isEqualTo(AMOUNT);
        assertThat(charge.getEntryType()).isEqualTo(EntryType.CHARGE);
        assertThat(charge.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(charge.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(charge.getSource()).isEqualTo(LedgerSource.REALTIME);
    }

    @Test
    @DisplayName("대사가 채우는 충전 보정은 배치가 기록한 것으로 남긴다")
    void 충전_보정() {
        PointLedger charge =
                PointLedger.charge(OWNER_ID, PAYMENT_ID, AMOUNT, LedgerSource.RECONCILIATION);

        assertThat(charge.getSource()).isEqualTo(LedgerSource.RECONCILIATION);
    }

    @Test
    @DisplayName("충전 금액이 0 이하면 기록할 수 없다")
    void 충전_금액() {
        assertThatThrownBy(() -> PointLedger.charge(OWNER_ID, PAYMENT_ID, 0, LedgerSource.REALTIME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.charge(OWNER_ID, PAYMENT_ID, -1, LedgerSource.REALTIME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /*
     * 결제 ID 가 충전의 멱등 키다. (payment_id, entry_type) 이 UNIQUE 라 같은 결제의 충전은
     * 한 줄뿐이다. 비어 있으면 그 보호가 사라져 같은 결제로 두 번 충전될 수 있다.
     */
    @Test
    @DisplayName("충전은 결제 ID가 필수다")
    void 충전_결제ID() {
        assertThatThrownBy(() -> PointLedger.charge(OWNER_ID, null, AMOUNT, LedgerSource.REALTIME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("충전에는 점주 ID가 필수다")
    void 충전_점주ID() {
        assertThatThrownBy(() -> PointLedger.charge(null, PAYMENT_ID, AMOUNT, LedgerSource.REALTIME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /*
     * 사람이 넣는 충전 경로는 없다.
     * 관리자의 가감은 ADJUST 로만 들어온다.
     */
    @Test
    @DisplayName("사람이 기록한 충전은 만들 수 없다")
    void 충전_수동_거부() {
        assertThatThrownBy(() -> PointLedger.charge(OWNER_ID, PAYMENT_ID, AMOUNT, LedgerSource.MANUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조정은 부호를 가진 금액을 그대로 저장하고 사람이 기록한 것으로 남긴다")
    void 조정_부호와_주체() {
        PointLedger plus = PointLedger.adjust(OWNER_ID, 1000L, "중복 과금 정정", 12L, TARGET, "req-1");
        PointLedger minus = PointLedger.adjust(OWNER_ID, -500L, "오입력 정정", null, null, "req-2");

        assertThat(plus.getAmount()).isEqualTo(1000L);
        assertThat(minus.getAmount()).isEqualTo(-500L);
        assertThat(plus.getEntryType()).isEqualTo(EntryType.ADJUST);
        assertThat(plus.getSource()).isEqualTo(LedgerSource.MANUAL);
        assertThat(plus.getReason()).isEqualTo("중복 과금 정정");
        assertThat(plus.getCampaignId()).isEqualTo(12L);
        assertThat(plus.getIdempotencyKey()).isEqualTo("req-1");
        assertThat(plus.getPaymentId()).isNull();
        assertThat(minus.getCampaignId()).isNull();
    }

    @Test
    @DisplayName("캠페인에 연결된 조정은 정정 대상 영업일을 저장하고 캠페인 없는 조정은 비워 둔다")
    void 조정_정정_대상_영업일() {
        PointLedger withCampaign = PointLedger.adjust(OWNER_ID, 1000L, "중복 과금 정정", 12L, TARGET, "req-1");
        PointLedger withoutCampaign = PointLedger.adjust(OWNER_ID, 1000L, "오입력 정정", null, null, "req-2");

        assertThat(withCampaign.getAdjustTargetDate()).isEqualTo(TARGET);
        assertThat(withoutCampaign.getAdjustTargetDate()).isNull();
    }

    @Test
    @DisplayName("캠페인과 정정 대상 영업일은 둘 다 있거나 둘 다 없어야 한다")
    void 조정_캠페인과_대상일_짝() {
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 1000L, "사유", 12L, null, "req"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 1000L, "사유", null, TARGET, "req"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조정 금액은 0일 수 없다")
    void 조정_0_거부() {
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 0L, "사유", null, null, "req"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조정 사유는 비어 있거나 255자를 넘을 수 없다")
    void 조정_사유_거부() {
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 100L, null, null, null, "req"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 100L, " ", null, null, "req"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 100L, "가".repeat(256), null, null, "req"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조정 멱등 키는 비어 있거나 64자를 넘을 수 없다")
    void 조정_멱등키_거부() {
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 100L, "사유", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 100L, "사유", null, null, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.adjust(OWNER_ID, 100L, "사유", null, null, "k".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("환불은 양수를 받아 음수로 저장하고 환불 요청에 묶는다")
    void 환불_부호() {
        PointLedger refund = PointLedger.refund(OWNER_ID, 31L, 12000L);

        assertThat(refund.getAmount()).isEqualTo(-12000L);
        assertThat(refund.getEntryType()).isEqualTo(EntryType.REFUND);
        assertThat(refund.getRefundRequestId()).isEqualTo(31L);
        assertThat(refund.getSource()).isEqualTo(LedgerSource.MANUAL);
        assertThat(refund.getPaymentId()).isNull();
        assertThat(refund.getCampaignId()).isNull();
    }

    @Test
    @DisplayName("환불은 환불 요청 ID 와 양수 금액이 필수다")
    void 환불_입력_거부() {
        assertThatThrownBy(() -> PointLedger.refund(OWNER_ID, null, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.refund(OWNER_ID, 31L, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정기 예약은 양수를 받아 음수로 저장하고 캠페인과 영업일에 묶는다")
    void 예약_부호() {
        PointLedger reserve = PointLedger.reserve(OWNER_ID, 5L, LocalDate.of(2026, 10, 16), 3000L);

        assertThat(reserve.getAmount()).isEqualTo(-3000L);
        assertThat(reserve.getEntryType()).isEqualTo(EntryType.RESERVE);
        assertThat(reserve.getCampaignId()).isEqualTo(5L);
        assertThat(reserve.getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(reserve.getSource()).isEqualTo(LedgerSource.RECONCILIATION);
        assertThat(reserve.getPaymentId()).isNull();
    }

    @Test
    @DisplayName("해제는 양수로 저장하고 캠페인과 영업일에 묶는다")
    void 해제_부호() {
        PointLedger release = PointLedger.release(OWNER_ID, 5L, LocalDate.of(2026, 10, 15), 2000L);

        assertThat(release.getAmount()).isEqualTo(2000L);
        assertThat(release.getEntryType()).isEqualTo(EntryType.RELEASE);
        assertThat(release.getCampaignId()).isEqualTo(5L);
        assertThat(release.getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(release.getSource()).isEqualTo(LedgerSource.RECONCILIATION);
    }

    /*
     * 정기 예약과 해제의 멱등 키는 캠페인과 영업일로 만든 생성 컬럼이다(uk_point_ledger_day_reserve_dedup).
     * 둘 중 하나가 비면 그 키가 NULL 이 되어 같은 날 예약이 두 번 들어갈 수 있다.
     */
    @Test
    @DisplayName("예약과 해제는 캠페인 ID, 영업일, 양수 금액이 필수다")
    void 예약_해제_입력_거부() {
        LocalDate day = LocalDate.of(2026, 10, 16);
        assertThatThrownBy(() -> PointLedger.reserve(OWNER_ID, null, day, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.reserve(OWNER_ID, 5L, null, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.reserve(OWNER_ID, 5L, day, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.release(OWNER_ID, 5L, day, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /*
     * 충전 직후 재예약(I-03)의 RESERVE 다. 00:00 정기 예약과 달리 결제 ID 를 가진다.
     * 멱등 키가 결제×캠페인(uk_point_ledger_recharge_reserve_dedup)이라 결제 ID 가 비면 그 보호가 사라진다.
     */
    @Test
    @DisplayName("충전 직후 재예약은 양수를 받아 음수로 저장하고 결제와 캠페인, 영업일에 묶는다")
    void 재예약_부호() {
        LocalDate day = LocalDate.of(2026, 10, 16);
        PointLedger reserve = PointLedger.rechargeReserve(OWNER_ID, PAYMENT_ID, 5L, day, 3000L);

        assertThat(reserve.getAmount()).isEqualTo(-3000L);
        assertThat(reserve.getEntryType()).isEqualTo(EntryType.RESERVE);
        assertThat(reserve.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(reserve.getCampaignId()).isEqualTo(5L);
        assertThat(reserve.getBusinessDate()).isEqualTo(day);
        assertThat(reserve.getSource()).isEqualTo(LedgerSource.RECONCILIATION);
    }

    @Test
    @DisplayName("충전 직후 재예약은 결제 ID, 캠페인 ID, 영업일, 양수 금액이 필수다")
    void 재예약_입력_거부() {
        LocalDate day = LocalDate.of(2026, 10, 16);
        assertThatThrownBy(() -> PointLedger.rechargeReserve(OWNER_ID, null, 5L, day, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.rechargeReserve(OWNER_ID, PAYMENT_ID, null, day, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.rechargeReserve(OWNER_ID, PAYMENT_ID, 5L, null, 100L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PointLedger.rechargeReserve(OWNER_ID, PAYMENT_ID, 5L, day, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
