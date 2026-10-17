package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.entity.SwipeAction;
import com.launchcatch.adserving.feed.entity.SwipeLog;
import com.launchcatch.adserving.feed.entity.SwipeLogId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SwipeLogRepository extends JpaRepository<SwipeLog, SwipeLogId>, SwipeLogInsert {

    // idx_swipe_member_date 로 그 사용자의 기간 안 찜만 업종별로 센다.
    default List<CategoryWishCount> countWishesByCategory(Long memberId, LocalDate from, LocalDate to) {
        return countByCategory(memberId, from, to, SwipeAction.WISH);
    }

    @Query("""
            select new com.launchcatch.adserving.feed.repository.CategoryWishCount(s.categoryCode, count(s))
            from SwipeLog s
            where s.id.memberId = :memberId
              and s.businessDate between :from and :to
              and s.action = :action
            group by s.categoryCode
            """)
    List<CategoryWishCount> countByCategory(@Param("memberId") Long memberId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to,
                                            @Param("action") SwipeAction action);

    // idx_swipe_date 로 그날 찜만 캠페인별로 센다.
    default List<CampaignWishCount> countWishesByCampaign(LocalDate businessDate) {
        return countByCampaign(businessDate, SwipeAction.WISH);
    }

    @Query("""
            select new com.launchcatch.adserving.feed.repository.CampaignWishCount(s.campaignId, count(s))
            from SwipeLog s
            where s.businessDate = :businessDate
              and s.action = :action
            group by s.campaignId
            """)
    List<CampaignWishCount> countByCampaign(@Param("businessDate") LocalDate businessDate,
                                            @Param("action") SwipeAction action);
}
