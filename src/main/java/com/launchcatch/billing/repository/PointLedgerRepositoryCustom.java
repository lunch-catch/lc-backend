package com.launchcatch.billing.repository;

import java.util.List;

// 조건이 동적으로 붙고 떨어지는 원장 조회다
public interface PointLedgerRepositoryCustom {

    // 기록 시각 내림차순, 같으면 ID 내림차순으로 limit 건까지 돌려준다
    List<LedgerRow> findOwnerLedger(OwnerLedgerCondition condition);
}
