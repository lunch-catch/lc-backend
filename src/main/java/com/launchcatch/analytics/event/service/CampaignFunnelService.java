package com.launchcatch.analytics.event.service;

import com.launchcatch.analytics.event.CampaignEventCounts;
import com.launchcatch.analytics.event.dto.CampaignFunnelResponse;
import com.launchcatch.analytics.event.repository.EventLogRepository;
import com.launchcatch.analytics.exception.AnalyticsErrorCode;
import com.launchcatch.analytics.exception.AnalyticsException;
import com.launchcatch.campaign.contract.CampaignQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignFunnelService {

    private static final int MAX_DAYS = 31;

    private final CampaignQueryService campaigns;
    private final EventLogRepository repository;

    public CampaignFunnelService(CampaignQueryService campaigns, EventLogRepository repository) {
        this.campaigns = campaigns;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public CampaignFunnelResponse query(Long ownerId, Long campaignId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new AnalyticsException(AnalyticsErrorCode.INVALID_PERIOD);
        }
        boolean owned = campaigns.findAllById(List.of(campaignId)).stream()
                .anyMatch(campaign -> ownerId.equals(campaign.ownerId()));
        if (!owned) {
            throw new AccessDeniedException("본인 캠페인만 조회할 수 있습니다.");
        }

        CampaignEventCounts counts = repository.countCampaignEvents(campaignId, from, to);
        return new CampaignFunnelResponse(campaignId, from, to,
                counts.impressionCount(), counts.wishCount(), counts.passCount(),
                counts.issuedCount(), counts.redeemCount(),
                rate(counts.wishCount(), counts.impressionCount()),
                rate(counts.passCount(), counts.impressionCount()),
                rate(counts.issuedCount(), counts.wishCount()),
                rate(counts.redeemCount(), counts.issuedCount()));
    }

    private static BigDecimal rate(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }
}
