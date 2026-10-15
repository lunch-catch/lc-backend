package com.launchcatch.adserving.feed.entity;

import com.launchcatch.adserving.engine.FeedSort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 사용자의 피드 필터다. 행이 없으면 거리순이다.
 * 저장은 피드 필터 API 가 맡고 피드는 읽기만 해서 생성 경로를 두지 않았다. 키가 member_id 라 BaseTimeEntity 를 상속하지 않는다.
 */
@Entity
@Table(name = "feed_filter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedFilter {

    @Id
    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sort_type", nullable = false)
    private FeedSort sortType;
}
