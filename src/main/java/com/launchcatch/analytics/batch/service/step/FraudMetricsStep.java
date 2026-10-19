package com.launchcatch.analytics.batch.service.step;

import com.launchcatch.adserving.contract.AdservingStatsQueryService;
import com.launchcatch.adserving.contract.CampaignImpressionStats;
import com.launchcatch.adserving.contract.CampaignMemberConcentration;
import com.launchcatch.analytics.batch.FraudMetrics;
import com.launchcatch.analytics.batch.repository.FraudAnalyticsRepository;
import com.launchcatch.analytics.batch.service.AggregationStep;
import com.launchcatch.analytics.config.AnalyticsFraudProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/*
 * 캠페인 부정 경고(A-10)의 일별 원천을 모아 daily_campaign_fraud_analytics 의 그날 행으로 교체한다.
 *
 * 유효와 무효 노출은 캠페인 통계에서, 유효 노출 회원 수와 상위 회원의 노출 합은 회원 집중 조회에서 받는다. 집중은
 * 영업일 단위로 판정한다. 그날 유효 노출이 최소 노출 이상이고 상위 회원의 점유율이 기준을 넘으면 의심이다.
 * 점유율이 기준과 같으면 넘은 것이 아니다.
 *
 * 무효 노출만 있는 캠페인도 행을 만든다. 서버가 발급하지 않은 serve_id 의 노출은 캠페인이 없을 수 있어도
 * 무효 비율 경고의 대상이다. 노출이 하나도 없는 캠페인은 행이 없다.
 */
@Component
@Order(520)
public class FraudMetricsStep implements AggregationStep<FraudMetrics> {

    private final AdservingStatsQueryService adserving;
    private final AnalyticsFraudProperties properties;
    private final FraudAnalyticsRepository repository;
    private final Clock clock;

    public FraudMetricsStep(
            AdservingStatsQueryService adserving,
            AnalyticsFraudProperties properties,
            FraudAnalyticsRepository repository,
            Clock clock) {
        this.adserving = adserving;
        this.properties = properties;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "FraudMetricsStep";
    }

    @Override
    public FraudMetrics collect(LocalDate businessDate) {
        Map<Long, CampaignMemberConcentration> concentrations = new HashMap<>();
        List<CampaignImpressionStats> stats = adserving.campaignStats(businessDate);
        for (CampaignMemberConcentration concentration
                : adserving.memberConcentrations(businessDate, properties.topMembers())) {
            concentrations.put(concentration.campaignId(), concentration);
        }
        List<FraudMetrics.Row> rows = stats.stream()
                .filter(campaign -> campaign.validCount() + invalidCount(campaign) > 0)
                .map(campaign -> toRow(campaign, concentrations.get(campaign.campaignId())))
                .toList();
        return new FraudMetrics(rows);
    }

    @Override
    public int publish(LocalDate businessDate, FraudMetrics collected) {
        repository.replace(businessDate, collected.rows(), LocalDateTime.now(clock));
        return collected.rows().size();
    }

    private FraudMetrics.Row toRow(CampaignImpressionStats campaign, CampaignMemberConcentration concentration) {
        long distinctMembers = concentration == null ? 0 : concentration.distinctMemberCount();
        long topMembers = concentration == null ? 0 : concentration.topMembersValidCount();
        return new FraudMetrics.Row(campaign.campaignId(), campaign.validCount(), invalidCount(campaign),
                distinctMembers, topMembers, concentrated(campaign.validCount(), topMembers));
    }

    private long invalidCount(CampaignImpressionStats campaign) {
        return campaign.expiredOrUnknownCount() + campaign.notOwnerCount();
    }

    private boolean concentrated(long validCount, long topMembersCount) {
        return validCount >= properties.minimumImpressions()
                && (double) topMembersCount / validCount > properties.concentrationShare();
    }
}
