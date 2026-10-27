package com.lunchcatch.analytics.query.service;

import com.lunchcatch.analytics.exception.AnalyticsErrorCode;
import com.lunchcatch.analytics.exception.AnalyticsException;
import com.lunchcatch.analytics.query.CampaignReportTotal;
import com.lunchcatch.analytics.query.repository.CampaignReportQueryRepository;
import com.lunchcatch.global.exception.CommonErrorCode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/*
 * 관리자 캠페인 리포트(A-14)다. 캠페인마다 기간 소진액과 누적 유효 노출을 campaign_daily_report 에서 합산한다.
 *
 * campaignIds 를 문자열로 받아 여기서 검사한다. 스프링이 목록을 바꾸다 실패하거나 필수 파라미터가 빠지면 전역 처리기가
 * COMMON-003 으로 답하는데, 명세는 그 경우를 COMMON-002 로 정했다.
 */
@Service
public class CampaignReportQueryService {

    private static final int MAX_CAMPAIGNS = 100;
    private static final int MAX_DAYS = 92;

    private final CampaignReportQueryRepository repository;

    public CampaignReportQueryService(CampaignReportQueryRepository repository) {
        this.repository = repository;
    }

    // from 과 to 를 둘 다 생략하면 각 캠페인의 집계된 전체 기간을 합한다
    public List<CampaignReportTotal> query(String campaignIds, LocalDate from, LocalDate to) {
        List<Long> ids = parse(campaignIds);
        validatePeriod(from, to);
        Map<Long, CampaignReportTotal> totals = repository.sumByCampaign(ids, from, to).stream()
                .collect(Collectors.toMap(CampaignReportTotal::campaignId, Function.identity()));
        return ids.stream()
                .map(id -> totals.getOrDefault(id, new CampaignReportTotal(id, 0, 0)))
                .toList();
    }

    // 쉼표로 나눈 양수 ID 를 요청 순서대로 돌려준다. 같은 ID 는 처음 자리에 한 번만 남긴다
    private List<Long> parse(String campaignIds) {
        if (campaignIds == null || campaignIds.isBlank()) {
            throw new AnalyticsException(CommonErrorCode.INVALID_INPUT);
        }
        String[] tokens = campaignIds.split(",", -1);
        if (tokens.length > MAX_CAMPAIGNS) {
            throw new AnalyticsException(CommonErrorCode.INVALID_INPUT);
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String token : tokens) {
            ids.add(positiveId(token.trim()));
        }
        return List.copyOf(ids);
    }

    private long positiveId(String token) {
        long id;
        try {
            id = Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new AnalyticsException(CommonErrorCode.INVALID_INPUT, e);
        }
        if (id <= 0) {
            throw new AnalyticsException(CommonErrorCode.INVALID_INPUT);
        }
        return id;
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return;
        }
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new AnalyticsException(AnalyticsErrorCode.INVALID_PERIOD);
        }
    }
}
