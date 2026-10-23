package com.launchcatch.adserving.engine.listener;

import com.launchcatch.adserving.engine.service.CandidateStatusService;
import com.launchcatch.campaign.contract.CampaignStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/*
 * 캠페인의 상태 변경을 받아 그날 후보의 서빙 상태를 바꾼다(#565).
 * 발행자와 같은 트랜잭션에서 받는다(아키텍처 설계 2.4). 반영이 실패하면 캠페인의 상태 전이도 되돌아가 둘이 어긋나지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CandidateStatusChangedListener {

    private final CandidateStatusService candidateStatusService;

    @EventListener
    public void on(CampaignStatusChangedEvent event) {
        candidateStatusService.changeServable(event.campaignId(), event.businessDate(), event.servable(), event.changedAt());
    }
}
