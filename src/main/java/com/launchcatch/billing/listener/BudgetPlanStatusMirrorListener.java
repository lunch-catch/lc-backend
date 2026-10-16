package com.launchcatch.billing.listener;

import com.launchcatch.billing.dto.BudgetPlanStatusChanged;
import com.launchcatch.billing.dto.BudgetPlanStatusConfirmed;
import com.launchcatch.billing.dto.RechargeReserveIncreased;
import com.launchcatch.billing.service.BudgetPlanStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 중단 표시가 커밋된 뒤에 Valkey 해시를 맞춘다. 롤백되면 불리지 않는다
@Component
@RequiredArgsConstructor
public class BudgetPlanStatusMirrorListener {

    private final BudgetPlanStatusService budgetPlanStatusService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(BudgetPlanStatusChanged changed) {
        budgetPlanStatusService.mirror(changed);
    }

    // 00:00 예약의 상태 확인이 바꾼 계획은 해시를 지워 DB 정본으로 다시 만들게 한다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(BudgetPlanStatusConfirmed confirmed) {
        budgetPlanStatusService.dropMirror(confirmed);
    }

    // 충전 직후 재예약이 늘린 예약액을 커밋된 뒤에 해시에 반영한다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(RechargeReserveIncreased increased) {
        budgetPlanStatusService.mirrorReserve(increased);
    }
}
