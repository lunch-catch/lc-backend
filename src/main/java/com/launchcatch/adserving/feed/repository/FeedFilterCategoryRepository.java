package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.entity.FeedFilterCategory;
import com.launchcatch.adserving.feed.entity.FeedFilterCategoryId;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedFilterCategoryRepository extends JpaRepository<FeedFilterCategory, FeedFilterCategoryId> {

    // 업종 코드 오름차순이다. 필터 조회가 늘 같은 순서로 돌려주려는 것이다(#405)
    @Query("select c.id.categoryCode from FeedFilterCategory c where c.id.memberId = :memberId order by c.id.categoryCode")
    List<String> findCategoryCodes(@Param("memberId") Long memberId);

    @Modifying
    @Query("delete from FeedFilterCategory c where c.id.memberId = :memberId")
    void deleteAllByMemberId(@Param("memberId") Long memberId);

    @Modifying
    @Query(value = """
            INSERT INTO feed_filter_category (member_id, category_code, created_at, updated_at)
            VALUES (:memberId, :categoryCode, :now, :now)
            """, nativeQuery = true)
    void insertCategory(@Param("memberId") Long memberId, @Param("categoryCode") String categoryCode,
            @Param("now") LocalDateTime now);
}
