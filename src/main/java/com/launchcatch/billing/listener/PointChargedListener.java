package com.launchcatch.billing.listener;

import com.launchcatch.billing.dto.PointCharged;
import com.launchcatch.billing.service.RechargeReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * 충전 커밋 뒤 재예약을 받는다(I-03). 커밋 뒤에 돌므로 재예약은 새 트랜잭션에서 돈다.
 *
 * 실패해도 예외를 밖으로 올리지 않고 ERROR 로만 남긴다. 충전은 이미 커밋돼 있어 올리면 승인 확정 API,
 * 웹훅, 재조회 워커가 성공한 충전에 실패를 돌려준다. 놓친 재예약은 다음 00:00 예약이 반영한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointChargedListener {

    private final RechargeReservationService rechargeReservation;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(PointCharged charged) {
        try {
            rechargeReservation.reserve(charged);
        } catch (Exception e) {
            log.error("event=RECHARGE_RESERVE_FAILED ownerId={} paymentId={}",
                    charged.ownerId(), charged.paymentId(), e);
        }
    }
}
