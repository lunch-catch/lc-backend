package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.BudgetDailyPlan;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    // 그날 계획 한 행이다. uk_budget_daily_plan_campaign_date 를 탄다
    Optional<BudgetDailyPlan> findByCampaignIdAndBusinessDate(Long campaignId, LocalDate businessDate);

    // 그날 여러 캠페인의 계획을 한 번에 읽는다. 행이 없는 캠페인은 결과에 없다
    List<BudgetDailyPlan> findByBusinessDateAndCampaignIdIn(LocalDate businessDate, Collection<Long> campaignIds);

    /*
     * 해시 재구성이 읽는다. SELECT ... FOR SHARE 로 읽어 트랜잭션이 끝날 때까지 행에 공유 잠금을 건다.
     *
     * 일시중지의 조건부 UPDATE(applyStatus)는 같은 행에 배타 잠금이 필요해서 이 트랜잭션이 끝날 때까지 기다린다.
     * 그래서 "계획을 읽었는데 그 뒤 커밋된 중단이 해시에 반영되지 않는" 경합이 없다. 중단이 먼저 커밋됐으면
     * 이 읽기가 그 값을 보고, 재구성이 먼저였으면 중단은 해시가 만들어진 뒤에 반영된다.
     * 잠금 읽기는 REPEATABLE READ 에서도 스냅샷이 아니라 최신 커밋을 본다.
     *
     * 노출 차감 핫패스는 이 메서드를 부르지 않는다. 해시가 없을 때만 재구성이 부른다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select p from BudgetDailyPlan p
             where p.campaignId = :campaignId
               and p.businessDate = :businessDate
            """)
    Optional<BudgetDailyPlan> findForShare(@Param("campaignId") Long campaignId,
                                           @Param("businessDate") LocalDate businessDate);

    /*
     * 여러 캠페인을 한 번에 잠그며 읽는다. 캠페인 ID 순으로 잠가서, 겹치는 캠페인을 다른 순서로 잠그는
     * 두 재구성이 서로를 기다리는 일이 없게 한다. 행이 없는 캠페인은 결과에 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
            select p from BudgetDailyPlan p
             where p.businessDate = :businessDate
               and p.campaignId in :campaignIds
             order by p.campaignId
            """)
    List<BudgetDailyPlan> findAllForShare(@Param("businessDate") LocalDate businessDate,
                                          @Param("campaignIds") Collection<Long> campaignIds);
}
