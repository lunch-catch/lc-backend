package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetDailyPlanRepository extends JpaRepository<BudgetDailyPlan, Long> {

    /*
     * 그날 계획 행의 중단 표시를 바꾼다. 저장된 시각보다 늦은 변경만 반영하고 반영한 행 수를 돌려준다.
     *
     * 읽고 비교한 뒤 쓰지 않고 조건부 UPDATE 한 문장으로 한다. 같은 캠페인의 변경 둘이 동시에 와도
     * 행 잠금 아래에서 비교와 쓰기가 함께 일어나므로, 도착 순서와 중복에 관계없이 늦은 쪽이 남는다.
     * 행이 없거나 더 늦은 변경이 이미 반영돼 있으면 0 이다.
     *
     * 영속성 컨텍스트를 비우지 않는다(clearAutomatically 를 쓰지 않는다). 발행자와 같은 트랜잭션에서
     * 돌기 때문에 비우면 발행자가 들고 있는 엔티티까지 분리되어 그 뒤의 변경이 사라진다.
     * 이 테이블의 엔티티를 같은 트랜잭션에서 읽어 두는 곳은 없다.
     */
    @Modifying
    @Query("""
            update BudgetDailyPlan p
               set p.paused = :paused,
                   p.statusChangedAt = :changedAt,
                   p.updatedAt = :now
             where p.campaignId = :campaignId
               and p.businessDate = :businessDate
               and (p.statusChangedAt is null or p.statusChangedAt < :changedAt)
            """)
    int applyStatus(@Param("campaignId") Long campaignId,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("paused") boolean paused,
                    @Param("changedAt") LocalDateTime changedAt,
                    @Param("now") LocalDateTime now);
}
