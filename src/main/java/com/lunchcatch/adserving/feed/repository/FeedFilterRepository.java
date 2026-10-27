package com.lunchcatch.adserving.feed.repository;

import com.lunchcatch.adserving.feed.entity.FeedFilter;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedFilterRepository extends JpaRepository<FeedFilter, Long> {

    /*
     * 행이 없으면 만들고 있으면 정렬만 바꾼다.
     * 이 문이 그 회원의 행을 잠가서, 같은 회원의 저장이 동시에 와도 뒤따르는 업종 교체가 차례로 돈다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO feed_filter (member_id, sort_type, created_at, updated_at)
            VALUES (:memberId, :sortType, :now, :now) AS new
            ON DUPLICATE KEY UPDATE sort_type = new.sort_type, updated_at = new.updated_at
            """, nativeQuery = true)
    void upsertSort(@Param("memberId") Long memberId, @Param("sortType") String sortType,
            @Param("now") LocalDateTime now);
}
