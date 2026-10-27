package com.lunchcatch.coupon.service;

import com.lunchcatch.coupon.CouponBatchTask;
import com.lunchcatch.coupon.CouponConsistencyFinding;
import com.lunchcatch.coupon.config.CouponProperties;
import com.lunchcatch.coupon.repository.CouponConsistencyRepository;
import com.lunchcatch.ops.contract.BatchAttemptGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class CouponConsistencyService {

    private final CouponProperties properties;

    private final CouponConsistencyRepository repository;
    private final BatchAttemptGuard guard;
    private final Clock clock;

    public CouponConsistencyService(CouponProperties properties, CouponConsistencyRepository repository, BatchAttemptGuard guard, Clock clock) {
        this.properties = properties;
        this.repository = repository;
        this.guard = guard;
        this.clock = clock;
    }

    /*
     * 모든 페이지가 같은 DB 스냅샷과 검사 시각을 사용해야 페이지 사이의 변경을 불일치로 오인하지 않는다.
     * 불일치 결과는 설정된 크기로 읽고 유형과 ID를 커서로 사용해 같은 대상을 반복 출력하지 않는다.
     * 원본은 고치지 않으며 실행기의 processed_count에는 발견한 불일치 수를 돌려준다.
     * 실행 세대 검사가 운영 행을 잠그므로 읽기 전용 트랜잭션으로 선언하지 않는다.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int verify(LocalDate date, int attemptNo) {
        guard.assertCurrentAttempt(CouponBatchTask.VERIFY.jobName(), date, attemptNo);
        LocalDateTime now = LocalDateTime.now(clock);
        String afterType = "";
        long afterId = 0;
        int mismatches = 0;
        List<CouponConsistencyFinding> findings;
        do {
            findings = repository.findings(date, afterType, afterId, now);
            for (CouponConsistencyFinding finding : findings) {
                log.error("event=COUPON_CONSISTENCY_MISMATCH businessDate={} type={} targetId={} expected={} actual={}",
                        date, finding.type(), finding.targetId(), finding.expectedValue(), finding.actualValue());
            }
            mismatches = Math.addExact(mismatches, findings.size());
            if (!findings.isEmpty()) {
                CouponConsistencyFinding last = findings.getLast();
                afterType = last.type();
                afterId = last.targetId();
            }
        } while (findings.size() == properties.batch().pageSize());
        if (mismatches > 0) {
            log.error("event=COUPON_CONSISTENCY_DONE businessDate={} mismatchCount={}", date, mismatches);
        }
        return mismatches;
    }
}
