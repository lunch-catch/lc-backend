package com.lunchcatch.adserving.feed.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 피드 필터에서 고른 업종 하나다. 0건이면 전체 업종이다.
 * 저장은 피드 필터 API 가 맡고 피드는 읽기만 해서 생성 경로를 두지 않았다.
 */
@Entity
@Table(name = "feed_filter_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedFilterCategory {

    @EmbeddedId
    private FeedFilterCategoryId id;
}
