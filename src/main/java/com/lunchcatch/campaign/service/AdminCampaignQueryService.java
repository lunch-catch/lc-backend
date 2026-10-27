package com.lunchcatch.campaign.service;

import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.dto.AdminCampaignDetailResponse;
import com.lunchcatch.campaign.dto.AdminCampaignListItemResponse;
import com.lunchcatch.campaign.dto.AdminCampaignPosterResponse;
import com.lunchcatch.campaign.dto.AdminCampaignStatusLogResponse;
import com.lunchcatch.campaign.entity.Campaign;
import com.lunchcatch.campaign.exception.CampaignErrorCode;
import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.poster.repository.PosterRepository;
import com.lunchcatch.campaign.repository.CampaignRepository;
import com.lunchcatch.campaign.repository.CampaignStatusLogRepository;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.PageCursor;
import com.lunchcatch.global.response.PageTokens;
import com.lunchcatch.store.contract.StoreInfo;
import com.lunchcatch.store.contract.StoreQueryService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자가 전체 캠페인을 조회한다(#603). 점주용 조회(CampaignOwnerQueryService)와 달리 소유자를
 * 가리지 않는다. 가게 상호는 목록 한 페이지의 가게 ID를 모아 한 번에 조회해 N+1을 피한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCampaignQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final CampaignRepository campaignRepository;
    private final CampaignStatusLogRepository campaignStatusLogRepository;
    private final PosterRepository posterRepository;
    private final StoreQueryService storeQueryService;

    public CursorPageResponse<AdminCampaignListItemResponse> list(
            Long storeId, CampaignStatus status, String pageToken, int pageSize) {
        int limit = clampPageSize(pageSize);
        Long cursorId = decodeCursorId(pageToken);

        List<Campaign> fetched =
                campaignRepository.findAdminPage(storeId, status, cursorId, PageRequest.of(0, limit + 1));
        boolean hasNext = fetched.size() > limit;
        List<Campaign> page = hasNext ? fetched.subList(0, limit) : fetched;

        Map<Long, String> storeNames = storeNames(page.stream().map(Campaign::getStoreId).distinct().toList());
        List<AdminCampaignListItemResponse> items = page.stream()
                .map(campaign -> AdminCampaignListItemResponse.from(campaign, storeNames.get(campaign.getStoreId())))
                .toList();
        String nextPageToken = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
        return CursorPageResponse.of(items, nextPageToken);
    }

    public AdminCampaignDetailResponse detail(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_FOUND));
        String storeName = storeNames(List.of(campaign.getStoreId())).get(campaign.getStoreId());
        List<AdminCampaignStatusLogResponse> statusLogs =
                campaignStatusLogRepository.findByCampaignIdOrderByIdAsc(campaignId).stream()
                        .map(AdminCampaignStatusLogResponse::from)
                        .toList();
        AdminCampaignPosterResponse poster = posterRepository.findByCampaignId(campaignId)
                .map(AdminCampaignPosterResponse::from)
                .orElse(null);
        return AdminCampaignDetailResponse.from(campaign, storeName, statusLogs, poster);
    }

    // 최종 등록 전 가게는 StoreQueryService.findAllById 가 제외하므로 그 가게의 상호는 null이다.
    private Map<Long, String> storeNames(List<Long> storeIds) {
        Map<Long, String> names = new HashMap<>();
        if (storeIds.isEmpty()) {
            return names;
        }
        for (StoreInfo store : storeQueryService.findAllById(storeIds)) {
            names.put(store.storeId(), store.storeName());
        }
        return names;
    }

    private int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    // PageTokens.decode()는 형식이 어긋난 토큰을 null로 돌려줘 첫 페이지로 본다(CampaignOwnerQueryService와 같다).
    private Long decodeCursorId(String pageToken) {
        PageCursor cursor = PageTokens.decode(pageToken);
        return cursor == null ? null : cursor.id();
    }

    private String encodeCursor(Campaign lastItem) {
        return PageTokens.encode(new PageCursor(lastItem.getId(), null));
    }
}
