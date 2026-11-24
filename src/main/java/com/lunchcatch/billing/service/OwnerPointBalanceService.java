package com.lunchcatch.billing.service;

import com.lunchcatch.billing.deduction.repository.DeductionResultRepository;
import com.lunchcatch.billing.dto.PointBalanceResponse;
import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.exception.BillingErrorCode;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 점주 잔액 조회다.
 *
 * 원장을 읽지 못하면 캐시나 이전 값으로 대신하지 않고 BILLING-006 으로 실패한다.
 * 금전 값이라 틀린 값을 보여 주는 것보다 실패를 드러내는 편이 낫다.
 * BILLING-006 으로 바꾸는 것은 DB 를 쓸 수 없는 경우뿐이다. 커넥션을 얻지 못함(CannotCreateTransactionException)과
 * DB 다운이나 연결 끊김(DataAccessResourceFailureException)이다(#1319).
 * SQL 문법 오류, 제약 위반, 트랜잭션 전파 위반은 다시 해도 같아 그대로 던져 전역 처리기의 500 이 되게 한다.
 * 쿼리 시간 초과도 넣지 않는다. 잠그지 않는 합계라 시간 초과는 쿼리나 인덱스의 문제일 가능성이 크다.
 *
 * 예약 중 포인트는 원장과 판정을 따로 읽는다(#1287). 두 조회 사이에 00:00 해제가 커밋되면 값이 어긋나므로
 * 세 조회를 한 트랜잭션에서 같은 스냅샷으로 읽는다. @Transactional 을 메서드에 달면 트랜잭션을 여는 실패가
 * 위 catch 밖에서 나 BILLING-006 으로 옮겨지지 않아, catch 안에서 TransactionTemplate 으로 연다.
 */
@Service
@RequiredArgsConstructor
public class OwnerPointBalanceService {

    private final PointLedgerRepository pointLedgerRepository;
    private final DeductionResultRepository deductionRepository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PointBalanceResponse getBalance(Long ownerId) {
        LocalDate today = LocalDate.now(clock);
        try {
            return transactions.execute(status -> {
                long balance = pointLedgerRepository.sumBalance(ownerId);
                long reserved = reservedPoint(ownerId);
                return new PointBalanceResponse(balance, reserved, balance + reserved, today);
            });
        } catch (CannotCreateTransactionException | DataAccessResourceFailureException e) {
            throw new BillingException(BillingErrorCode.LEDGER_UNAVAILABLE, e);
        }
    }

    /*
     * 오늘 영업일에 예약했지만 아직 쓰지도 돌려받지도 않은 금액이다.
     * 원장의 예약 크기에서 해제 크기를 빼고 판정의 BILLED 단가 합을 뺀다. 소진은 판정이 정본이다(#1287).
     * 해제는 다음 날 00:00 이라 오늘 예약분은 하루 종일 남는다. 지난 날은 해제가 예약에서 소진을 뺀 만큼이라 0 이 된다.
     */
    private long reservedPoint(Long ownerId) {
        long notReleased = pointLedgerRepository.sumReservedNotReleased(ownerId);
        return Math.max(0, notReleased - deductionRepository.sumBilledForOwner(ownerId));
    }
}
