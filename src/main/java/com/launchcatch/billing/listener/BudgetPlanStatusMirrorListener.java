package com.launchcatch.billing.listener;

import com.launchcatch.billing.dto.BudgetPlanStatusChanged;
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
}
