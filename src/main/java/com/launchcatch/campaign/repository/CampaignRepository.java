package com.launchcatch.campaign.repository;

import com.launchcatch.campaign.contract.CouponIssueCondition;
import com.launchcatch.campaign.entity.Campaign;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    /*
     * 생성자 표현식으로 record 를 바로 만든다. 엔티티를 꺼내 서비스에서 옮겨 담지 않는 이유는
     * 필요한 컬럼 넷만 읽으면 되고, 엔티티를 거치면 영속 상태를 쓰지 않는 조회가 1차 캐시를
     * 채우기 때문이다.
     */
    @Query("""
            select new com.launchcatch.campaign.contract.CouponIssueCondition(
                c.id, c.issueQuantity, c.usableStartTime, c.usableEndTime)
            from Campaign c
            where c.id = :campaignId and c.status = com.launchcatch.campaign.entity.CampaignStatus.ACTIVE
            """)
    Optional<CouponIssueCondition> findActiveCondition(@Param("campaignId") Long campaignId);
}
