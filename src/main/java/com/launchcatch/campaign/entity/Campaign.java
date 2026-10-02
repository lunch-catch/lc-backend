package com.launchcatch.campaign.entity;

import com.launchcatch.global.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 캠페인. 지금은 쿠폰이 발급 조건을 읽는 경로만 필요해서 그 조회에 쓰이는 컬럼만 매핑했다.
 * 캠페인 생성과 수정은 캠페인 도메인 담당자의 기능이라 이 PR 에 생성 경로(EC-2-03, R2)를
 * 두지 않았다. 쓰기를 붙일 때 필수 컬럼 전체와 함께 정적 팩터리를 만든다.
 *
 * PK 가 id 가 아니라 campaign_id 라서 AttributeOverride 로 베이스 엔티티의 id 를 옮긴다.
 * V1 스키마가 테이블마다 {테이블}_id 를 쓰므로 모든 엔티티가 같은 처리를 하게 된다.
 * 베이스 엔티티에서 @Id 를 빼는 쪽이 나을지는 팀이 한 번 정해야 한다.
 */
@Entity
@Table(name = "campaign")
@AttributeOverride(name = "id", column = @Column(name = "campaign_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Campaign extends BaseMutableTimeEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CampaignStatus status;

    @Column(name = "issue_quantity", nullable = false)
    private int issueQuantity;

    @Column(name = "usable_start_time", nullable = false)
    private LocalTime usableStartTime;

    @Column(name = "usable_end_time", nullable = false)
    private LocalTime usableEndTime;
}
