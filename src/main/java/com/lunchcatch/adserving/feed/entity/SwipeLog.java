package com.lunchcatch.adserving.feed.entity;

import com.lunchcatch.adserving.contract.SwipeAction;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 스와이프 한 번의 기록이다. (serveId, 요청 사용자)마다 처음 넘긴 것 하나다(FR-065).
 * 피드는 최근 30일의 업종 선호도를 셀 때 읽는다.
 * 같은 키의 두 번째 저장은 INSERT IGNORE 로 넘겨야 해서 저장은 저장소의 insertIgnore 가 한다.
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

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    private SwipeAction action;

    // 클라이언트에서 넘긴 시각이다
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    private SwipeLog(SwipeLogId id, LocalDate businessDate, Long campaignId, String categoryCode,
                     SwipeAction action, LocalDateTime occurredAt) {
        this.id = Objects.requireNonNull(id);
        this.businessDate = Objects.requireNonNull(businessDate);
        this.campaignId = Objects.requireNonNull(campaignId);
        this.categoryCode = Objects.requireNonNull(categoryCode);
        this.action = Objects.requireNonNull(action);
        this.occurredAt = Objects.requireNonNull(occurredAt);
    }

    // 업종은 그날 후보에서 복사한다. 30일 선호도를 셀 때 후보가 이미 사라졌어도 셀 수 있다.
    public static SwipeLog of(UUID serveId, Long memberId, LocalDate businessDate, Long campaignId,
                              String categoryCode, SwipeAction action, LocalDateTime occurredAt) {
        return new SwipeLog(SwipeLogId.of(serveId, memberId), businessDate, campaignId, categoryCode, action,
                occurredAt);
    }
}
