package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.PaymentDetailResponse;
import com.launchcatch.billing.dto.PaymentHistoryItem;
import com.launchcatch.billing.entity.Payment;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PaymentRepository;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주의 결제 상태와 결제 내역을 읽는다. 항상 로그인한 점주 자신의 결제만 읽는다.
 *
 * 남의 결제는 404(BILLING-004)다. 점주 역할은 이미 통과했고 "내 결제 중에 없다" 는 뜻이라
 * 권한 거부(403)가 아니다. 403 으로 답하면 그 ID 의 결제가 있다는 사실이 드러난다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentRepository paymentRepository;

    public PaymentDetailResponse get(Long ownerId, Long paymentId) {
        Payment payment = paymentRepository.findByIdAndOwnerId(paymentId, ownerId)
                .orElseThrow(() -> new BillingException(BillingErrorCode.PAYMENT_NOT_FOUND));
        return PaymentDetailResponse.from(payment);
    }

    /*
     * 요청 시각 내림차순 커서 목록이다. 한 건 더 읽어 다음 페이지가 있는지 판정한다.
     * 토큰을 읽지 못하면 첫 페이지로 본다(PageTokens 규약).
     */
    public CursorPageResponse<PaymentHistoryItem> list(Long ownerId, String pageToken, int pageSize) {
        int size = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        PageRequest limit = PageRequest.of(0, size + 1);
        PageCursor cursor = PageTokens.decode(pageToken);
        LocalDateTime cursorCreatedAt = cursor == null ? null : parseOrNull(cursor.sortValue());

        List<Payment> rows = cursorCreatedAt == null
                ? paymentRepository.findOwnerHistory(ownerId, limit)
                : paymentRepository.findOwnerHistoryAfter(ownerId, cursorCreatedAt, cursor.id(), limit);

        boolean hasNext = rows.size() > size;
        List<Payment> page = hasNext ? rows.subList(0, size) : rows;
        String nextPageToken = hasNext ? nextToken(page.get(page.size() - 1)) : null;
        return CursorPageResponse.of(page.stream().map(PaymentHistoryItem::from).toList(), nextPageToken);
    }

    private LocalDateTime parseOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String nextToken(Payment last) {
        return PageTokens.encode(new PageCursor(last.getId(), last.getCreatedAt().toString()));
    }
}
