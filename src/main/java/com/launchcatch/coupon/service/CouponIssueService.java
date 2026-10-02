package com.launchcatch.coupon.service;

import com.launchcatch.campaign.contract.CouponIssueCondition;
import com.launchcatch.campaign.contract.CouponIssueConditionQueryService;
import com.launchcatch.coupon.contract.CouponIssuedEvent;
import java.time.Clock;
import java.time.LocalTime;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 쿠폰 발급. 캠페인의 발급 조건은 campaign.contract 로만 읽는다.
 * 캠페인의 엔티티나 리포지토리를 직접 보면 설계 문서 2.2 절의 간선이 계약을 지나지 않게 된다.
 */
@Service
class CouponIssueService {

    private final CouponIssueConditionQueryService issueConditionQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    CouponIssueService(CouponIssueConditionQueryService issueConditionQueryService,
                       ApplicationEventPublisher eventPublisher,
                       Clock clock) {
        this.issueConditionQueryService = issueConditionQueryService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /*
     * 발급 가능하면 이벤트를 알리고 true 를 돌려준다.
     * 활성 캠페인이 아니거나 쓸 수 있는 시간대가 아니면 발급하지 않는다.
     */
    @Transactional
    public boolean issue(Long campaignId, Long memberId) {
        LocalDateTime now = LocalDateTime.now(clock);
        CouponIssueCondition condition = issueConditionQueryService.findActiveCondition(campaignId)
                .orElse(null);
        if (condition == null || !usableAt(condition, now.toLocalTime())) {
            return false;
        }
        eventPublisher.publishEvent(new CouponIssuedEvent(campaignId, memberId, now));
        return true;
    }

    // 쿠폰을 쓸 수 있는 시간대 안인지 본다. 시작은 포함하고 끝은 제외한다
    private boolean usableAt(CouponIssueCondition condition, LocalTime time) {
        return !time.isBefore(condition.usableStartTime())
                && time.isBefore(condition.usableEndTime());
    }
}
