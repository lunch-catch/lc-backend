package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.PointAdjustmentRequest;
import com.launchcatch.billing.dto.PointAdjustmentResponse;
import com.launchcatch.billing.dto.PointAdjustmentResult;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.entity.PointLedger;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentRepository;
import com.launchcatch.billing.repository.PointAccountRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import com.launchcatch.billing.repository.SettlementMismatchRepository;
import com.launchcatch.billing.repository.SettlementMismatchRow;
import com.launchcatch.campaign.contract.CampaignInfo;
import com.launchcatch.campaign.contract.CampaignQueryService;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 포인트 조정을 한 트랜잭션에서 기록한다. 원장 줄, 대사 건 닫기, 감사 로그가 함께 커밋되거나 함께 롤백된다.
 *
 * 양수 조정도 점주 계정 행을 잠근다(구현 계획 G-6). 같은 키의 동시 요청이 줄을 서므로 키를 먼저 읽어 판정해도 정확하다.
 * 잠금 순서는 point_account, point_ledger, settlement_mismatch 로 고정한다. payment 는 잠그지 않는다.
 * 충전 승인이 payment 다음에 point_account 를 잠그므로, 여기서 payment 를 잠그면 순서가 엇갈려 교착할 수 있다.
 *
 * 잠금이 이 트랜잭션의 첫 조회여야 한다. MySQL 기본 격리 수준에서 일반 SELECT 는 처음 읽은 시점의 스냅샷을 계속 본다.
 * 잠그기 전에 다른 것을 먼저 읽으면, 잠금 뒤의 잔액 합이 그사이 커밋된 예약이나 환불을 보지 못한다.
 */
@Service
@RequiredArgsConstructor
public class PointAdjustmentRecorder {

    static final String AUDIT_ACTION = "POINT_ADJUST";

    private final PointAccountRepository pointAccountRepository;
    private final PointLedgerRepository pointLedgerRepository;
    private final SettlementMismatchRepository settlementMismatchRepository;
    private final PaymentRepository paymentRepository;
    private final CampaignQueryService campaignQueryService;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    @Transactional
    public PointAdjustmentResult record(Long adminId, PointAdjustmentRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        lockAccount(request.ownerId(), now);

        PointLedger existing = pointLedgerRepository
                .findByOwnerIdAndIdempotencyKey(request.ownerId(), request.requestId())
                .orElse(null);
        if (existing != null) {
            return replay(existing, request);
        }
        if (request.mismatchId() != null) {
            checkMismatch(request);
        }
        if (request.amount() < 0 && pointLedgerRepository.sumBalance(request.ownerId()) + request.amount() < 0) {
            throw new BillingException(BillingErrorCode.ADJUSTMENT_MAKES_BALANCE_NEGATIVE);
        }

        PointLedger ledger = pointLedgerRepository.save(PointLedger.adjust(request.ownerId(), request.amount(),
                request.reason(), request.campaignId(), request.targetBusinessDate(), request.requestId()));
        // 영향 행이 0 이면 대사 건을 읽은 뒤 다른 경로가 먼저 닫은 것이므로 원장 줄까지 함께 롤백한다
        if (request.mismatchId() != null
                && settlementMismatchRepository.resolveAsOverchargeReversal(request.mismatchId(), adminId, now) == 0) {
            throw new BillingException(BillingErrorCode.SETTLEMENT_MISMATCH_ALREADY_RESOLVED);
        }
        auditLogWriter.write(adminId, AUDIT_ACTION, String.valueOf(request.ownerId()),
                "ledgerId=" + ledger.getId() + ";amount=" + request.amount() + ";campaignId=" + request.campaignId()
                        + ";mismatchId=" + request.mismatchId() + ";requestId=" + request.requestId());
        return new PointAdjustmentResult(PointAdjustmentResponse.from(ledger), true);
    }

    /*
     * 점주 계정 행을 잠근다. 행이 없으면 만들고, 그 INSERT 가 새 행을 잠근다.
     * 잠금 대기 상한(5초)을 넘기면 잠시 뒤 같은 요청으로 다시 시도하라고 503 으로 답한다.
     */
    private void lockAccount(Long ownerId, LocalDateTime now) {
        try {
            if (pointAccountRepository.lockByOwnerId(ownerId).isEmpty()) {
                pointAccountRepository.createIfAbsent(ownerId, now);
            }
        } catch (PessimisticLockingFailureException e) {
            throw new BillingException(BillingErrorCode.LEDGER_UNAVAILABLE, e);
        }
    }

    /*
     * 같은 점주가 같은 키로 다시 보낸 요청이다. 내용이 같으면 처음 기록을 돌려주고, 다르면 거절한다.
     * mismatchId 는 원장에 저장하지 않아 비교하지 않는다(구현 계획 G-5). 대사 건도 다시 검사하지 않는다.
     */
    private PointAdjustmentResult replay(PointLedger existing, PointAdjustmentRequest request) {
        boolean same = existing.getAmount() == request.amount()
                && Objects.equals(existing.getCampaignId(), request.campaignId())
                && Objects.equals(existing.getAdjustTargetDate(), request.targetBusinessDate())
                && Objects.equals(existing.getReason(), request.reason());
        if (!same) {
            throw new BillingException(BillingErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return new PointAdjustmentResult(PointAdjustmentResponse.from(existing), false);
    }

    /*
     * 닫으려는 대사 건이 열려 있고 이 점주의 것인지 본다(구현 계획 G-4).
     * 캠페인 대상 건은 조정의 campaignId 가 대상 캠페인과 같아야 한다. 서버가 채우지 않는 이유는
     * 채우면 같은 키 재요청에서 저장값과 요청값이 달라 보여 BILLING-018 을 잘못 내기 때문이다.
     */
    private void checkMismatch(PointAdjustmentRequest request) {
        SettlementMismatchRow mismatch = settlementMismatchRepository.findRowById(request.mismatchId())
                .orElseThrow(() -> new BillingException(BillingErrorCode.SETTLEMENT_MISMATCH_NOT_FOUND));
        if (mismatch.resolutionType() != null) {
            throw new BillingException(BillingErrorCode.SETTLEMENT_MISMATCH_ALREADY_RESOLVED);
        }
        boolean campaignTarget = mismatch.checkType().targetsCampaign();
        Long targetOwnerId = campaignTarget ? campaignOwnerId(mismatch.targetId()) : paymentOwnerId(mismatch.targetId());
        if (!request.ownerId().equals(targetOwnerId)) {
            throw new BillingException(BillingErrorCode.SETTLEMENT_MISMATCH_OWNER_MISMATCH);
        }
        if (campaignTarget && !mismatch.targetId().equals(request.campaignId())) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        // 캠페인 대사 건의 영업일이 정정이 가리키는 영업일이다. 다르면 엉뚱한 날의 합계가 고쳐진다
        if (campaignTarget && !mismatch.businessDate().equals(request.targetBusinessDate())) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
    }

    // 잠그지 않는 일반 읽기이며 결제가 없으면 null 이라 어느 점주와도 같지 않다
    private Long paymentOwnerId(Long paymentId) {
        return paymentRepository.findById(paymentId).map(Payment::getOwnerId).orElse(null);
    }

    private Long campaignOwnerId(Long campaignId) {
        List<CampaignInfo> campaigns = campaignQueryService.findAllById(List.of(campaignId));
        return campaigns.isEmpty() ? null : campaigns.get(0).ownerId();
    }
}
