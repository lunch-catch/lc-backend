package com.launchcatch.adserving.feed.repository;

import com.launchcatch.adserving.feed.entity.FeedFilterCategory;
import com.launchcatch.adserving.feed.entity.FeedFilterCategoryId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedFilterCategoryRepository extends JpaRepository<FeedFilterCategory, FeedFilterCategoryId> {

    @Query("select c.id.categoryCode from FeedFilterCategory c where c.id.memberId = :memberId")
    List<String> findCategoryCodes(@Param("memberId") Long memberId);
}
