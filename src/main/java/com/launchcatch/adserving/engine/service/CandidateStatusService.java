package com.launchcatch.adserving.engine.service;

import com.launchcatch.adserving.engine.entity.CandidateCampaignState;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 캠페인이 낮에 멈추거나 재개되면 그날 후보의 서빙 상태를 바꿔 다음 피드 요청부터 반영한다(FR-092, FR-093).
 * 서빙할 수 없으면 PAUSED, 있으면 SERVABLE 이다. 낮에 끝난 캠페인도 PAUSED 로 둔다. 피드에서 빠지는 결과가 같고 다음 날 후보에 들어가지 않는다(#565).
 */
@Service
@RequiredArgsConstructor
public class CandidateStatusService {

    /*
     * 상태 통보를 받았는데 그날 후보가 없던 횟수다.
     * 00:00 후보 적재가 없거나 빠졌는지 본다.
     */
    static final String CANDIDATE_MISSING_METRIC = "adserving_status_candidate_missing_total";

    private final AdCandidateRepository adCandidateRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /*
     * 바뀐 행이 없으면 더 늦은 전이가 이미 반영됐거나 그날 후보가 없는 것이다. 후보가 없을 때만 센다.
     * 후보가 없어도 예외를 던지지 않는다. 캠페인의 상태 전이까지 되돌아가고, 00:00 후보 적재가 없는 동안 늘 그렇다.
     */
    @Transactional
    public void changeServable(Long campaignId, LocalDate businessDate, boolean servable, LocalDateTime changedAt) {
        CandidateCampaignState state = servable ? CandidateCampaignState.SERVABLE : CandidateCampaignState.PAUSED;
        int updated = adCandidateRepository.changeState(businessDate, campaignId, state.name(), changedAt,
                LocalDateTime.now(clock));
        if (updated == 0 && !adCandidateRepository.existsByIdBusinessDateAndIdCampaignId(businessDate, campaignId)) {
            meterRegistry.counter(CANDIDATE_MISSING_METRIC).increment();
        }
    }
}
