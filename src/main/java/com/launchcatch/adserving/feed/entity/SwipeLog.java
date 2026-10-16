package com.launchcatch.adserving.feed.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 스와이프 한 번의 기록이다. 피드는 최근 30일의 업종 선호도를 셀 때 읽기만 한다.
 * 저장은 스와이프 API 가 맡아 생성 경로를 두지 않았고 피드가 쓰는 컬럼만 매핑한다.
 */
@Entity
@Table(name = "swipe_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SwipeLog {

    @EmbeddedId
    private SwipeLogId id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "category_code", nullable = false)
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private SwipeAction action;
}
