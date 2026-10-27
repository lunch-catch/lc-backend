package com.lunchcatch.campaign.entity;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * FR-092 "모든 전이는 감사 로그로 기록"의 저장소다. 캠페인 애그리거트 안에 있지만
 * Campaign 이 이 로그 목록을 들고 있을 필요는 없어(조회 기능이 이 이슈 범위 밖) ops.AuditLog
 * 처럼 campaignId 를 원시값으로만 들고 쓰고 잊는 로그로 둔다.
 */
@Entity
@Table(name = "campaign_status_log")
@AttributeOverride(name = "id", column = @Column(name = "campaign_status_log_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CampaignStatusLog extends BaseTimeEntity {

    private static final int REASON_MAX_LENGTH = 200;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private CampaignStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private CampaignStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ActorType actorType;

    @Column(length = REASON_MAX_LENGTH)
    private String reason;

    private CampaignStatusLog(
            Long campaignId, CampaignStatus fromStatus, CampaignStatus toStatus, ActorType actorType,
            String reason) {
        if (fromStatus == toStatus) {
            throw new IllegalArgumentException("fromStatus and toStatus must differ: " + fromStatus);
        }
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
        this.fromStatus = Objects.requireNonNull(fromStatus, "fromStatus");
        this.toStatus = Objects.requireNonNull(toStatus, "toStatus");
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.reason = reason;
    }

    public static CampaignStatusLog of(
            Long campaignId, CampaignStatus fromStatus, CampaignStatus toStatus, ActorType actorType,
            String reason) {
        return new CampaignStatusLog(campaignId, fromStatus, toStatus, actorType, reason);
    }
}
