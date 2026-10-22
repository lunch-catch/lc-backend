package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.PointBalanceResponse;
import com.launchcatch.billing.entity.EntryType;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PointLedgerRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/*
 * 점주 잔액 조회다.
 *
 * 원장을 읽지 못하면 캐시나 이전 값으로 대신하지 않고 BILLING-006 으로 실패한다.
 * 금전 값이라 틀린 값을 보여 주는 것보다 실패를 드러내는 편이 낫다.
 * 커넥션을 얻지 못한 실패는 DataAccessException 이 아니라 TransactionException 으로 오기 때문에 둘을 함께 잡는다.
 */
@Service
@RequiredArgsConstructor
public class OwnerPointBalanceService {

    private final PointLedgerRepository pointLedgerRepository;
    private final Clock clock;

    public PointBalanceResponse getBalance(Long ownerId) {
        LocalDate today = LocalDate.now(clock);
        try {
            long balance = pointLedgerRepository.sumBalance(ownerId);
            long reserved = reservedPoint(ownerId, today);
            return new PointBalanceResponse(balance, reserved, balance + reserved, today);
        } catch (DataAccessException | TransactionException e) {
            throw new BillingException(BillingErrorCode.LEDGER_UNAVAILABLE, e);
        }
    }

    /*
     * 오늘 영업일에 예약했지만 아직 쓰지도 돌려받지도 않은 금액이다.
     * 저장값의 부호는 RESERVE 와 DEDUCT 가 음수, RELEASE 가 양수라서 크기로 옮기면
     * 예약 크기 - 차감 크기 - 해제 크기가 된다. 해제는 다음 날 00:00 이라 오늘 예약분은 하루 종일 남는다.
     */
    private long reservedPoint(Long ownerId, LocalDate today) {
        long reserve = pointLedgerRepository.sumByBusinessDate(ownerId, EntryType.RESERVE, today);
        long release = pointLedgerRepository.sumByBusinessDate(ownerId, EntryType.RELEASE, today);
        long deduct = pointLedgerRepository.sumByCreatedAt(ownerId, EntryType.DEDUCT,
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        return Math.max(0, -reserve + deduct - release);
    }
}
