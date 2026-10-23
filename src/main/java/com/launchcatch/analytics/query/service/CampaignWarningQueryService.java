package com.launchcatch.analytics.query.service;

import com.launchcatch.analytics.config.AnalyticsFraudProperties;
import com.launchcatch.analytics.query.CampaignWarning;
import com.launchcatch.analytics.query.CampaignWarningResult;
import com.launchcatch.analytics.query.CampaignWarningSort;
import com.launchcatch.analytics.query.CampaignWarningType;
import com.launchcatch.analytics.query.FraudDateRange;
import com.launchcatch.analytics.query.InvalidRatio;
import com.launchcatch.analytics.query.SortDirection;
import com.launchcatch.analytics.query.repository.CampaignFraudRow;
import com.launchcatch.analytics.query.repository.FraudQueryRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/*
 * A-10 캠페인 경고다. 일별 캠페인 집계를 기간 합계로 묶어 두 경고를 판정하고, 하나라도 걸린 캠페인을 내려준다.
 *
 * 무효 비율은 기간의 유효와 무효 합계로 다시 계산한다. 집중 경고는 일 단위로 이미 판정해 둔 값을 쓰고 기간 중
 * 하루라도 의심이었으면 걸린다. 경고가 걸린 캠페인은 많아야 수천이라 정렬과 쪽 나누기는 메모리에서 한다. 비율
 * 식을 SQL 에 한 번 더 쓰지 않기 위해서다.
 */
@Service
public class CampaignWarningQueryService {

    private final FraudQueryRepository repository;
    private final AnalyticsFraudProperties properties;

    public CampaignWarningQueryService(FraudQueryRepository repository, AnalyticsFraudProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public CampaignWarningResult query(LocalDate from, LocalDate to, CampaignWarningType type, int page, int size,
            String sortBy, String sortDir) {
        FraudDateRange range = new FraudDateRange(from, to);
        CampaignWarningSort sort = CampaignWarningSort.from(sortBy);
        SortDirection direction = SortDirection.from(sortDir);

        List<CampaignWarning> warnings = repository.findCampaignFraud(range.from(), range.to()).stream()
                .map(this::judge)
                .filter(warning -> matches(warning, type))
                .sorted(comparator(sort, direction))
                .toList();
        int start = (int) Math.min((long) page * size, warnings.size());
        int end = Math.min(start + size, warnings.size());
        return new CampaignWarningResult(warnings.subList(start, end), page, size, warnings.size());
    }

    private CampaignWarning judge(CampaignFraudRow row) {
        return new CampaignWarning(row.campaignId(), row.validCount(), row.invalidCount(),
                InvalidRatio.of(row.validCount(), row.invalidCount()),
                InvalidRatio.warning(row.validCount(), row.invalidCount(), properties.minimumInvalidCount()),
                row.distinctMemberCount(), row.topMembersShare(), row.concentrationSuspected());
    }

    private boolean matches(CampaignWarning warning, CampaignWarningType type) {
        if (type == CampaignWarningType.INVALID_RATIO) {
            return warning.invalidRatioWarning();
        }
        if (type == CampaignWarningType.CONCENTRATION) {
            return warning.concentrationWarning();
        }
        return warning.invalidRatioWarning() || warning.concentrationWarning();
    }

    /*
     * 같은 값이면 캠페인 번호 오름차순이다. 비율이 없는 경고(유효 노출 0)는 비율이 무한대인 셈이라 내림차순에서는 맨
     * 앞, 오름차순에서는 맨 뒤에 둔다.
     */
    private Comparator<CampaignWarning> comparator(CampaignWarningSort sort, SortDirection direction) {
        Comparator<CampaignWarning> primary = switch (sort) {
            case INVALID_RATIO -> Comparator.comparingDouble(
                    warning -> warning.invalidRatio() == null ? Double.POSITIVE_INFINITY : warning.invalidRatio());
            case TOP_MEMBERS_SHARE -> Comparator.comparingDouble(CampaignWarning::topMembersShare);
            case CAMPAIGN_ID -> Comparator.comparingLong(CampaignWarning::campaignId);
        };
        Comparator<CampaignWarning> ordered = direction == SortDirection.DESC ? primary.reversed() : primary;
        return ordered.thenComparingLong(CampaignWarning::campaignId);
    }
}
