package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.RefundProcessResponse;
import com.lunchcatch.billing.entity.PointLedger;
import com.lunchcatch.billing.entity.RefundRequest;
import com.lunchcatch.billing.entity.RefundStatus;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PointAccountRepository;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.billing.repository.RefundRequestRepository;
import com.lunchcatch.campaign.contract.CampaignQueryService;
import com.lunchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 환불 승인을 한 트랜잭션에서 기록한다. 원장 REFUND, 요청의 완료, 감사 로그가 함께 커밋되거나 함께 롤백된다.
 *
 * 잠금 순서는 점주 계정 행, 환불 요청 행, 원장으로 고정한다. 계정 행 잠금이 이 트랜잭션의 첫 조회여야
 * 뒤의 잔액 합이 그사이 커밋된 예약과 조정을 본다. 그래서 요청의 점주 ID 는 트랜잭션 밖에서 미리 읽어 받는다.
 * 반려는 계정 행을 잡지 않고 요청 행만 잠그므로 순서가 엇갈리지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RefundApprovalRecorder {

    static final String AUDIT_ACTION = "REFUND_APPROVE";

    private final PointAccountRepository pointAccountRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final CampaignQueryService campaignQueryService;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    @Transactional
    public RefundProcessResponse approve(Long adminId, Long refundRequestId, Long ownerId) {
        lockAccount(ownerId);
        // 점주 ID 를 읽을 때 있던 요청이고 요청은 지우지 않으므로 반드시 있다
        RefundRequest request = refundRequestRepository.findByIdForUpdate(refundRequestId).orElseThrow();
        if (request.getStatus() != RefundStatus.REQUESTED) {
            throw new BillingException(BillingErrorCode.REFUND_REQUEST_ALREADY_PROCESSED);
        }
        // 요청한 뒤에 캠페인이 생겼을 수 있어 다시 확인한다
        if (campaignQueryService.hasLiveCampaign(ownerId)) {
            throw new BillingException(BillingErrorCode.LIVE_CAMPAIGN_EXISTS);
        }
        // 요청 뒤의 예약과 음수 조정으로 잔액이 요청 금액보다 줄었으면 환불 뒤 잔액이 음수가 되므로 처리하지 않는다
        if (pointLedgerRepository.sumBalance(ownerId) < request.getAmount()) {
            throw new BillingException(BillingErrorCode.INSUFFICIENT_BALANCE);
        }

        pointLedgerRepository.save(PointLedger.refund(ownerId, refundRequestId, request.getAmount()));
        request.complete(adminId, LocalDateTime.now(clock));
        auditLogWriter.write(adminId, AUDIT_ACTION, String.valueOf(refundRequestId),
                "ownerId=" + ownerId + ";amount=" + request.getAmount());
        return RefundProcessResponse.from(request);
    }

    // 잠금 대기 상한을 넘기면 잠시 뒤 다시 시도하라고 503 으로 답한다
    private void lockAccount(Long ownerId) {
        try {
            pointAccountRepository.lockByOwnerId(ownerId);
        } catch (PessimisticLockingFailureException e) {
            throw new BillingException(BillingErrorCode.LEDGER_UNAVAILABLE, e);
        }
    }
}
