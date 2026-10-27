package com.lunchcatch.campaign.service;

import com.lunchcatch.campaign.contract.CampaignStatsQueryService;
import com.lunchcatch.campaign.repository.CampaignStatusLogRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CampaignStatsQueryServiceImpl implements CampaignStatsQueryService {

    private final CampaignStatusLogRepository campaignStatusLogRepository;
    private final Clock clock;

    /*
     * businessDate가 오늘이거나 미래면 거부한다(#354 리뷰). 그날이 끝나기 전에 부르면 그날
     * 안에 남은 전이가 로그에 아직 없어, 그 시점까지만 맞는 값을 "그날의 최종 값"처럼 돌려줄
     * 수 있다. 호출하는 쪽의 날짜 계산이 하루 어긋나도 예외 없이 그럴듯한 값을 돌려주는 것을
     * 막는다. 분석은 13:30 대조 이후 끝난 영업일만 부르기로 되어 있다(설계 문서 2.2).
     */
    @Override
    public long activeCampaignCount(LocalDate businessDate) {
        LocalDate today = LocalDate.now(clock);
        if (!businessDate.isBefore(today)) {
            throw new IllegalArgumentException(
                    "businessDate must be before today(" + today + "): " + businessDate);
        }
        return campaignStatusLogRepository.countActiveAsOf(businessDate.plusDays(1).atStartOfDay());
    }
}
