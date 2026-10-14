package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.RefundBlockedReason;
import com.launchcatch.billing.dto.RefundPreviewResponse;
import com.launchcatch.billing.dto.RefundRequestCreateResponse;
import com.launchcatch.billing.entity.RefundRequest;
import com.launchcatch.billing.entity.RefundStatus;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.RefundRequestRepository;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.global.exception.ConstraintViolations;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주의 환불 미리보기와 환불 요청이다. 환불은 잔액 전액만 되고 요청만으로는 원장이 바뀌지 않는다.
 *
 * 요청은 점주 계정 행을 잠근 뒤 잔액 없음, 집행 중 캠페인, 대기 요청 순서로 검사한다(BILLING-012, 010, 011).
 * 잠금이 이 트랜잭션의 첫 조회여야 뒤의 잔액 합이 그사이 커밋된 예약과 조정을 본다.
 * 계정 행이 없는 점주는 충전한 적이 없어 잔액이 0 이므로 행을 만들지 않고 바로 거절한다.
 */
@Service
@RequiredArgsConstructor
public class OwnerRefundService {

    static final String PENDING_UNIQUE = "uk_refund_request_pending";

    private final PointAccountRepository pointAccountRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final CampaignQueryService campaignQueryService;

    @Transactional(readOnly = true)
    public RefundPreviewResponse preview(Long ownerId) {
        long balance = pointLedgerRepository.sumBalance(ownerId);
        RefundBlockedReason blocked = blockedReason(ownerId, balance);
        long refundable = Math.max(0, balance);
        return new RefundPreviewResponse(refundable, 0L, refundable, blocked == null, blocked);
    }

    @Transactional
    public RefundRequestCreateResponse request(Long ownerId, String reason) {
        if (!lockAccount(ownerId)) {
            throw new BillingException(BillingErrorCode.NO_REFUNDABLE_BALANCE);
        }
        long balance = pointLedgerRepository.sumBalance(ownerId);
        RefundBlockedReason blocked = blockedReason(ownerId, balance);
        if (blocked != null) {
            throw new BillingException(errorCode(blocked));
        }
        try {
            return RefundRequestCreateResponse.from(
                    refundRequestRepository.save(RefundRequest.requestAll(ownerId, balance, reason)));
        } catch (DataIntegrityViolationException e) {
            // 잠금이 대기 요청 검사를 직렬화하지만 마지막 방어선은 점주당 대기 요청 하나를 보장하는 UNIQUE 다
            if (ConstraintViolations.isConstraintViolation(e, PENDING_UNIQUE)) {
                throw new BillingException(BillingErrorCode.REFUND_REQUEST_ALREADY_PENDING, e);
            }
            throw e;
        }
    }

    /*
     * 계정 행을 잠그고 행이 있었는지 돌려준다.
     * 잠금 대기 상한을 넘기면 잠시 뒤 다시 시도하라고 503 으로 답한다.
     */
    private boolean lockAccount(Long ownerId) {
        try {
            return pointAccountRepository.lockByOwnerId(ownerId).isPresent();
        } catch (PessimisticLockingFailureException e) {
            throw new BillingException(BillingErrorCode.LEDGER_UNAVAILABLE, e);
        }
    }

    // 요청 API 의 검사 순서대로 첫 번째로 막히는 이유를 돌려주고, 막히지 않으면 null 이다
    private RefundBlockedReason blockedReason(Long ownerId, long balance) {
        if (balance <= 0) {
            return RefundBlockedReason.NO_BALANCE;
        }
        if (campaignQueryService.hasLiveCampaign(ownerId)) {
            return RefundBlockedReason.ACTIVE_CAMPAIGN;
        }
        if (refundRequestRepository.existsByOwnerIdAndStatus(ownerId, RefundStatus.REQUESTED)) {
            return RefundBlockedReason.PENDING_REQUEST;
        }
        return null;
    }

    private static BillingErrorCode errorCode(RefundBlockedReason reason) {
        return switch (reason) {
            case NO_BALANCE -> BillingErrorCode.NO_REFUNDABLE_BALANCE;
            case ACTIVE_CAMPAIGN -> BillingErrorCode.LIVE_CAMPAIGN_EXISTS;
            case PENDING_REQUEST -> BillingErrorCode.REFUND_REQUEST_ALREADY_PENDING;
        };
    }
}
