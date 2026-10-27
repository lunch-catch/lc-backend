package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.CampaignCounterMismatch;
import com.lunchcatch.billing.dto.CampaignOverspend;
import com.lunchcatch.billing.repository.SettlementMismatchRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 전일 대사(SpendReconciliationService)가 찾은 불일치를 기록한다. 한 번 부르는 것이 트랜잭션 하나다.
 *
 * 대사는 Valkey 카운터를 읽으므로 트랜잭션을 걸지 않는다. Valkey 응답이 늦어지는 동안 DB 연결과 트랜잭션을 쥐고
 * 있지 않도록, DB 에 쓰는 이 부분만 따로 짧게 묶는다. 같은 클래스 안에서 부르면 프록시를 거치지 않아 트랜잭션이
 * 걸리지 않으므로 빈을 나눈다.
 *
 * 같은 (종류, 캠페인, 영업일) 이 이미 있으면 그대로 둔다(uk_settlement_mismatch_check_target_date 무변경 upsert).
 */
@Service
@RequiredArgsConstructor
public class SpendMismatchRecorder {

    private final SettlementMismatchRepository mismatchRepository;

    @Transactional
    public void record(LocalDate businessDate, Long jobExecutionId, LocalDateTime now,
                       List<CampaignOverspend> overspends, List<CampaignCounterMismatch> counterMismatches) {
        recordOverspends(businessDate, jobExecutionId, now, overspends);
        for (CampaignCounterMismatch mismatch : counterMismatches) {
            mismatchRepository.insertCampaignSpendMismatchIfAbsent(businessDate, mismatch.campaignId(),
                    mismatch.ledgerSpend(), mismatch.counterSpend(), jobExecutionId, now);
        }
    }

    private void recordOverspends(LocalDate businessDate, Long jobExecutionId, LocalDateTime now,
                                  List<CampaignOverspend> overspends) {
        for (CampaignOverspend overspend : overspends) {
            mismatchRepository.insertCampaignOverspendIfAbsent(businessDate, overspend.campaignId(),
                    overspend.reserved(), overspend.ledgerSpend(), jobExecutionId, now);
        }
    }
}
