package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.launchcatch.global.exception.ErrorCode;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class OwnerRefundServiceTest {

    private static final Long OWNER = 7L;

    private final PointAccountRepository accountRepository = mock(PointAccountRepository.class);
    private final PointLedgerRepository ledgerRepository = mock(PointLedgerRepository.class);
    private final RefundRequestRepository refundRepository = mock(RefundRequestRepository.class);
    private final CampaignQueryService campaignQueryService = mock(CampaignQueryService.class);
    private final OwnerRefundService service =
            new OwnerRefundService(accountRepository, ledgerRepository, refundRepository, campaignQueryService);

    @BeforeEach
    void 계정_행이_있고_잔액이_있으며_막히는_것이_없다() {
        when(accountRepository.lockByOwnerId(OWNER)).thenReturn(Optional.of(1L));
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(12000L);
        when(campaignQueryService.hasLiveCampaign(OWNER)).thenReturn(false);
        when(refundRepository.existsByOwnerIdAndStatus(OWNER, RefundStatus.REQUESTED)).thenReturn(false);
        when(refundRepository.save(any())).thenAnswer(invocation -> {
            RefundRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", 31L);
            return request;
        });
    }

    private void assertRejected(ErrorCode expected) {
        assertThatThrownBy(() -> service.request(OWNER, "서비스 중단"))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void 미리보기는_잔액_전액을_수수료_0으로_보여_준다() {
        RefundPreviewResponse preview = service.preview(OWNER);

        assertThat(preview.refundablePoint()).isEqualTo(12000L);
        assertThat(preview.fee()).isZero();
        assertThat(preview.expectedRefundAmount()).isEqualTo(12000L);
        assertThat(preview.canRequest()).isTrue();
        assertThat(preview.blockedReason()).isNull();
    }

    @Test
    void 미리보기는_막히는_이유가_여럿이면_요청_검사_순서의_첫_번째를_보여_준다() {
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(0L);
        when(campaignQueryService.hasLiveCampaign(OWNER)).thenReturn(true);
        when(refundRepository.existsByOwnerIdAndStatus(OWNER, RefundStatus.REQUESTED)).thenReturn(true);

        RefundPreviewResponse preview = service.preview(OWNER);

        assertThat(preview.canRequest()).isFalse();
        assertThat(preview.blockedReason()).isEqualTo(RefundBlockedReason.NO_BALANCE);
        assertThat(preview.refundablePoint()).isZero();
    }

    @Test
    void 미리보기는_잔액이_있으면_집행_중_캠페인을_대기_요청보다_먼저_보여_준다() {
        when(campaignQueryService.hasLiveCampaign(OWNER)).thenReturn(true);
        when(refundRepository.existsByOwnerIdAndStatus(OWNER, RefundStatus.REQUESTED)).thenReturn(true);

        assertThat(service.preview(OWNER).blockedReason()).isEqualTo(RefundBlockedReason.ACTIVE_CAMPAIGN);
    }

    @Test
    void 미리보기는_대기_요청이_있으면_요청할_수_없다고_보여_준다() {
        when(refundRepository.existsByOwnerIdAndStatus(OWNER, RefundStatus.REQUESTED)).thenReturn(true);

        assertThat(service.preview(OWNER).blockedReason()).isEqualTo(RefundBlockedReason.PENDING_REQUEST);
    }

    @Test
    void 요청은_잔액_전액으로_대기_요청을_만든다() {
        RefundRequestCreateResponse response = service.request(OWNER, "서비스 중단");

        ArgumentCaptor<RefundRequest> saved = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRepository).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualTo(12000L);
        assertThat(saved.getValue().getReason()).isEqualTo("서비스 중단");
        assertThat(response.refundRequestId()).isEqualTo(31L);
        assertThat(response.amount()).isEqualTo(12000L);
        assertThat(response.fee()).isZero();
        assertThat(response.status()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void 계정_행이_없는_점주는_충전한_적이_없어_행을_만들지_않고_거절한다() {
        when(accountRepository.lockByOwnerId(OWNER)).thenReturn(Optional.empty());

        assertRejected(BillingErrorCode.NO_REFUNDABLE_BALANCE);

        verify(accountRepository, never()).createIfAbsent(any(), any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    void 잔액이_없으면_거절한다() {
        when(ledgerRepository.sumBalance(OWNER)).thenReturn(0L);

        assertRejected(BillingErrorCode.NO_REFUNDABLE_BALANCE);
    }

    @Test
    void 집행_중인_캠페인이_있으면_거절한다() {
        when(campaignQueryService.hasLiveCampaign(OWNER)).thenReturn(true);

        assertRejected(BillingErrorCode.LIVE_CAMPAIGN_EXISTS);
    }

    @Test
    void 대기_요청이_있으면_거절한다() {
        when(refundRepository.existsByOwnerIdAndStatus(OWNER, RefundStatus.REQUESTED)).thenReturn(true);

        assertRejected(BillingErrorCode.REFUND_REQUEST_ALREADY_PENDING);
        verify(refundRepository, never()).save(any());
    }

    @Test
    void 잠금을_얻지_못하면_잠시_뒤_다시_시도하게_한다() {
        when(accountRepository.lockByOwnerId(OWNER)).thenThrow(new CannotAcquireLockException("lock wait timeout"));

        assertRejected(BillingErrorCode.LEDGER_UNAVAILABLE);
    }

    @Test
    void 대기_요청_UNIQUE에_걸리면_대기_요청이_있다고_답한다() {
        // BeforeEach 의 save 스텁을 다시 실행하지 않도록 doThrow 로 덮어쓴다
        doThrow(new DataIntegrityViolationException("dup",
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry '7' for key 'refund_request.uk_refund_request_pending'")))
                .when(refundRepository).save(any());

        assertRejected(BillingErrorCode.REFUND_REQUEST_ALREADY_PENDING);
    }

    @Test
    void 다른_제약_위반은_그대로_던진다() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("other",
                new SQLIntegrityConstraintViolationException("Check constraint 'chk_refund_request_amount' is violated."));
        doThrow(other).when(refundRepository).save(any());

        assertThatThrownBy(() -> service.request(OWNER, null)).isSameAs(other);
    }
}
