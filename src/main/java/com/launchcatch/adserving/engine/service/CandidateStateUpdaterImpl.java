package com.launchcatch.adserving.engine.service;

import com.launchcatch.adserving.contract.CandidateStateUpdater;
import com.launchcatch.adserving.engine.repository.AdCandidateRepository;
import com.launchcatch.adserving.engine.repository.CandidateChangeNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CandidateStateUpdaterImpl implements CandidateStateUpdater {

    static final String CANDIDATE_MISSING_METRIC = "adserving_sold_out_candidate_missing_total";

    private final AdCandidateRepository adCandidateRepository;
    private final CandidateChangeNotifier candidateChangeNotifier;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /*
     * 받은 소진 시각을 그대로 쓰고 부른 시각으로 바꾸지 않는다. 이미 소진된 후보는 처음 시각을 남긴다.
     * 후보가 없어도 예외를 던지지 않는다. 쿠폰은 실패해도 발급을 유지해서 받아도 할 일이 없고, 00:00 후보 적재가 없는 동안 늘 실패한다(#163).
     */
    @Override
    @Transactional
    public void markSoldOut(Long campaignId, LocalDate businessDate, LocalDateTime soldOutAt) {
        int updated = adCandidateRepository.markSoldOut(businessDate, campaignId, soldOutAt, LocalDateTime.now(clock));
        if (updated > 0) {
            candidateChangeNotifier.changed(businessDate, campaignId);
        }
        if (updated == 0 && !adCandidateRepository.existsByIdBusinessDateAndIdCampaignId(businessDate, campaignId)) {
            meterRegistry.counter(CANDIDATE_MISSING_METRIC).increment();
        }
    }
}
