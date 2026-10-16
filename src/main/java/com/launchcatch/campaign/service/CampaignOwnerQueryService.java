package com.launchcatch.campaign.service;

import com.launchcatch.campaign.dto.CampaignDetailResponse;
import com.launchcatch.campaign.dto.CampaignListItemResponse;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주가 본인 소유 캠페인을 조회한다(#341). 등록(쿠폰 조건·노출 대상·예산과 기간) 단계
 * 서비스와 달리 상태를 바꾸지 않아 OwnerStatusQuery(ACTIVE 확인)를 쓰지 않는다. 단순
 * 조회는 상태를 확인하지 않는 owner-api-spec.md의 GET /v1/owner/me와 같은 선례다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CampaignOwnerQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final CampaignRepository campaignRepository;

    public CursorPageResponse<CampaignListItemResponse> list(Long ownerId, String pageToken, int pageSize) {
        int limit = clampPageSize(pageSize);
        Long cursorId = decodeCursorId(pageToken);
        Pageable pageable = PageRequest.of(0, limit + 1);

        List<Campaign> fetched = campaignRepository.findPageByOwnerId(ownerId, cursorId, pageable);
        boolean hasNext = fetched.size() > limit;
        List<Campaign> page = hasNext ? fetched.subList(0, limit) : fetched;

        List<CampaignListItemResponse> items = page.stream().map(CampaignListItemResponse::from).toList();
        String nextPageToken = hasNext ? encodeCursor(page.get(page.size() - 1)) : null;
        return CursorPageResponse.of(items, nextPageToken);
    }

    public CampaignDetailResponse detail(Long ownerId, Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        return CampaignDetailResponse.from(campaign);
    }

    private int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    // PageTokens.decode()는 형식이 어긋난 토큰을 null로 돌려줘 첫 페이지로 본다(TemplateQueryService와 같다).
    private Long decodeCursorId(String pageToken) {
        PageCursor cursor = PageTokens.decode(pageToken);
        return cursor == null ? null : cursor.id();
    }

    private String encodeCursor(Campaign lastItem) {
        return PageTokens.encode(new PageCursor(lastItem.getId(), null));
    }
}
