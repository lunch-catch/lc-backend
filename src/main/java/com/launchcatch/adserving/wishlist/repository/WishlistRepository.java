package com.launchcatch.adserving.wishlist.repository;

import com.launchcatch.adserving.wishlist.entity.Wishlist;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WishlistRepository extends JpaRepository<Wishlist, Long>, WishlistInsert {

    // 찜한 시각 내림차순이다. 같은 시각이면 나중에 넣은 행을 앞에 둔다.
    List<Wishlist> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId);

    Optional<Wishlist> findByMemberIdAndCampaignId(Long memberId, Long campaignId);

    // 캠페인이 끝난 찜은 3일 동안 남지만 유효하지 않다(#548)
    boolean existsByMemberIdAndCampaignIdAndCampaignEndedAtIsNull(Long memberId, Long campaignId);

    // 지운 행 수를 돌려준다. 찜하지 않았으면 0 이다
    @Transactional
    @Modifying
    @Query("delete from Wishlist w where w.memberId = :memberId and w.campaignId = :campaignId")
    int deleteByMemberIdAndCampaignId(@Param("memberId") Long memberId, @Param("campaignId") Long campaignId);

    @Query("select w.campaignId from Wishlist w where w.memberId = :memberId and w.campaignEndedAt is null")
    List<Long> findActiveCampaignIdsByMemberId(@Param("memberId") Long memberId);

    // 이미 종료로 표시된 찜은 그대로 둔다. 같은 종료 이벤트가 다시 와도 처음 종료 시각이 남는다. idx_wishlist_campaign 을 탄다
    @Transactional
    @Modifying
    @Query("""
            update Wishlist w set w.campaignEndedAt = :endedAt, w.updatedAt = :now
             where w.campaignId = :campaignId and w.campaignEndedAt is null
            """)
    int markCampaignEnded(@Param("campaignId") Long campaignId, @Param("endedAt") LocalDateTime endedAt,
                          @Param("now") LocalDateTime now);

    // cutoff 전에 끝난 캠페인의 찜을 지운다. idx_wishlist_campaign_ended 를 탄다
    @Transactional
    @Modifying
    @Query("delete from Wishlist w where w.campaignEndedAt < :cutoff")
    int deleteByCampaignEndedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
