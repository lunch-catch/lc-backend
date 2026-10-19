package com.launchcatch.adserving.wishlist.repository;

import com.launchcatch.adserving.wishlist.entity.Wishlist;
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

    boolean existsByMemberIdAndCampaignId(Long memberId, Long campaignId);

    // 지운 행 수를 돌려준다. 찜하지 않았으면 0 이다
    @Transactional
    @Modifying
    @Query("delete from Wishlist w where w.memberId = :memberId and w.campaignId = :campaignId")
    int deleteByMemberIdAndCampaignId(@Param("memberId") Long memberId, @Param("campaignId") Long campaignId);

    @Query("select w.campaignId from Wishlist w where w.memberId = :memberId")
    List<Long> findCampaignIdsByMemberId(@Param("memberId") Long memberId);
}
