package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.RefundProcessResponse;
import com.launchcatch.billing.entity.RefundRequest;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.RefundRequestRepository;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자의 환불 승인과 반려다. 환불은 mock 이라 결제대행사 취소 없이 원장 REFUND 만 기록한다.
 *
 * 승인은 요청의 점주 ID 를 트랜잭션 밖에서 읽고 RefundApprovalRecorder 에 넘긴다. 그 트랜잭션은 점주 계정 행을 먼저 잠가야 한다.
 * 반려는 원장을 건드리지 않아 계정 행을 잡지 않고, 요청 행을 잠근 뒤 상태를 보고 바꾼다.
 * 승인과 반려가 동시에 와도 요청 행 잠금 때문에 뒤에 온 쪽이 BILLING-015 로 멈춘다.
 */
@Service
@RequiredArgsConstructor
public class AdminRefundProcessService {

    static final String REJECT_AUDIT_ACTION = "REFUND_REJECT";

    private final RefundRequestRepository refundRequestRepository;
    private final RefundApprovalRecorder approvalRecorder;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RefundProcessResponse approve(Long adminId, Long refundRequestId) {
        Long ownerId = refundRequestRepository.findOwnerIdById(refundRequestId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.REFUND_REQUEST_NOT_FOUND));
        return approvalRecorder.approve(adminId, refundRequestId, ownerId);
    }

    @Transactional
    public RefundProcessResponse reject(Long adminId, Long refundRequestId, String rejectReason) {
        RefundRequest request = refundRequestRepository.findByIdForUpdate(refundRequestId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.REFUND_REQUEST_NOT_FOUND));
        if (request.getStatus() != RefundStatus.REQUESTED) {
            throw new BillingException(BillingErrorCode.REFUND_REQUEST_ALREADY_PROCESSED);
        }
        request.reject(adminId, rejectReason, LocalDateTime.now(clock));
        auditLogWriter.write(adminId, REJECT_AUDIT_ACTION, String.valueOf(refundRequestId),
                "ownerId=" + request.getOwnerId() + ";amount=" + request.getAmount());
        return RefundProcessResponse.from(request);
    }
}
