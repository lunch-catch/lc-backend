package com.launchcatch.billing.service;

import com.launchcatch.billing.contract.DeductionStatus;
import com.launchcatch.billing.contract.PointDeductor;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.cache.DailySpendCounter;
import com.launchcatch.billing.cache.DeductOutcome;
import com.launchcatch.billing.repository.DeductionResultRepository;
import com.launchcatch.billing.repository.PointLedgerRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 유효 노출 1건의 과금을 판정하고 기록한다(I-01).
 *
 * 순서는 ① 그날 계획의 점주와 단가 ② 당일 소진 해시에서 판정 ③ 판정 기록 ④ BILLED 면 원장 DEDUCT 다.
 * 해시가 없으면 DB 정본으로 한 번 만들고 다시 판정한다. 그래도 없으면 예외다.
 * 판정 기록과 원장은 무변경 갱신 INSERT 라서 같은 serveId 의 재호출이 예외 없이 지나가고, 판정은 Valkey 가
 * 첫 결과를 돌려준다. 잔액은 읽지 않는다.
 *
 * 노출을 저장하는 트랜잭션 안에서만 부른다. 밖에서 부르면 Spring 이 바로 예외를 낸다(E-5).
 * 금전 값이라 대체값을 돌려주지 않는다. 실패는 예외이고 노출 저장까지 롤백된다.
 */
/*
 * TODO(성능 측정): 이 클래스가 노출 차감 부하 테스트의 측정 대상이다. 초당 1만 건(비기능 17행)에 못 미치면
 *   "노출 차감 성능 개선 선택지.md" 4장 순서대로 이 클래스와 아래 저장소, 스크립트, 스키마를 바꾸고
 *   ./gradlew perfTest 로 전후를 비교한다. 하네스는 PointDeductor 만 부르므로 바꾸지 않는다.
 *   하네스를 같이 고쳐야 하는 경우(0단계 구간 분해, 명세를 바꾸는 4단계)는 "노출 차감 부하 테스트.md" 2.4절.
 */
@Service
@RequiredArgsConstructor
public class PointDeductorImpl implements PointDeductor {

    private final DailyPlanSnapshotService snapshotService;
    private final SpendHashRebuildService rebuildService;
    private final DailySpendCounter spendCounter;
    private final DeductionResultRepository deductionResultRepository;
    private final PointLedgerRepository ledgerRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DeductionStatus deduct(UUID serveId, Long campaignId, LocalDate businessDate) {
        PlanSnapshot plan = snapshotService.get(businessDate, campaignId);

        /*
         * TODO(성능 측정): Valkey 왕복 1회(측정 0-a). 0-a 만으로 1만 건이 안 나오면 DB 개선은 소용없다.
         * Lua 내용과 커넥션 설정을 먼저 본다.
         */
        DeductOutcome outcome = spendCounter.deduct(businessDate, campaignId, serveId, plan.unitPrice());
        if (outcome.isHashMissing()) {
            rebuildService.rebuildIfAbsent(businessDate, campaignId);
            outcome = spendCounter.deduct(businessDate, campaignId, serveId, plan.unitPrice());
            if (outcome.isHashMissing()) {
                throw new IllegalStateException(
                        "재구성한 뒤에도 당일 소진 해시가 없습니다. campaignId=" + campaignId + ", businessDate=" + businessDate);
            }
        }
        DeductionStatus status = outcome.status();
        LocalDateTime now = LocalDateTime.now(clock);

        // TODO: 노출당 INSERT 2건(deduction_result, point_ledger)이 초당 1만 건을 못 버티면 "노출 차감 성능 개선 선택지.md"를 보고 개선한다
        /*
         * TODO(성능 측정): INSERT 1/2(측정 0-b, 0-b'). 바꿀 후보: A serveId v7(광고 서빙 발급),
         * C-1 기본 키를 serve_id 로(마이그레이션), B-1 두 INSERT 를 한 요청으로(차감 전용 데이터소스),
         * B-2 BILLED 면 이 INSERT 생략(명세 변경, 4단계)
         */
        deductionResultRepository.insertIfAbsent(serveId, campaignId, plan.ownerId(), businessDate, status, plan.unitPrice(), now);

        if (status == DeductionStatus.BILLED) {
            /*
             * TODO(성능 측정): INSERT 2/2. 바꿀 후보: C-2 DEDUCT 가 쓰지 않는 원장 조회 인덱스 점검(마이그레이션),
             * B-1 위와 같음, F 분 단위 묶음 행(명세 변경, 4단계).
             * 커밋 비용(fsync)이 병목이면 이 자리가 아니라 E-1 커넥션 풀 설정을 본다
             */
            ledgerRepository.insertDeductIfAbsent(plan.ownerId(), campaignId, serveId, -plan.unitPrice(), now);
        }
        return status;
    }
}
