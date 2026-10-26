package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.SettlementMismatchItem;
import com.launchcatch.billing.entity.MismatchResolutionType;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.SettlementMismatchRepository;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자가 원장을 바꾸지 않고 대사 불일치를 닫는다(NO_ADJUSTMENT_REQUIRED).
 *
 * 사유는 받지 않는다. 프론트와의 계약을 resolutionType 값 말고는 바꾸지 않기로 해서 사유를 둘 자리가 없다. 처리자와 시각만 남는다.
 * 포인트 조정과 같은 건을 두고 겨뤄도 둘 다 resolution_type IS NULL 조건으로 갱신하므로 먼저 커밋한 쪽만 닫는다.
 * 점주 계정 행은 잠그지 않는다. 원장을 쓰지 않아 잔액과 겨룰 것이 없고, 대사 건 행 하나만 잠가 조정의 잠금 순서와 엇갈리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SettlementMismatchResolutionService {

    static final String AUDIT_ACTION = "SETTLEMENT_MISMATCH_CLOSE";

    private final SettlementMismatchRepository settlementMismatchRepository;
    private final SettlementMismatchQueryService queryService;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    @Transactional
    public SettlementMismatchItem closeWithoutAdjustment(Long adminId, Long mismatchId) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (settlementMismatchRepository.resolveByAdmin(mismatchId, MismatchResolutionType.NO_ADJUSTMENT_REQUIRED,
                adminId, now) == 0) {
            throw rejection(mismatchId);
        }
        auditLogWriter.write(adminId, AUDIT_ACTION, String.valueOf(mismatchId),
                "resolutionType=" + MismatchResolutionType.NO_ADJUSTMENT_REQUIRED);
        return queryService.get(mismatchId);
    }

    // 갱신이 0 행이면 건이 없거나 이미 닫힌 것이다. 다시 읽어 둘을 가른다
    private BillingException rejection(Long mismatchId) {
        return new BillingException(settlementMismatchRepository.findRowById(mismatchId).isPresent()
                ? BillingErrorCode.SETTLEMENT_MISMATCH_ALREADY_RESOLVED
                : BillingErrorCode.SETTLEMENT_MISMATCH_NOT_FOUND);
    }
}
