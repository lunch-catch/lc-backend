package com.launchcatch.campaign.poster.service;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.exception.CampaignErrorCode;
import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.exception.PosterErrorCode;
import com.launchcatch.campaign.poster.dto.PosterCreateResponse;
import com.launchcatch.campaign.poster.dto.PosterResponse;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.repository.CampaignRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 점주용·사용자용 포스터 조회(#495). 명세와 그 근거는
 * docs/api-spec/poster-api-spec.md, docs/design/campaign/poster-api-design.md의
 * 같은 이름 절에 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PosterQueryService {

    private static final Set<CampaignStatus> PUBLIC_STATUSES =
            Set.of(CampaignStatus.ACTIVE, CampaignStatus.PAUSED, CampaignStatus.ENDED);

    private final CampaignRepository campaignRepository;
    private final PosterRepository posterRepository;

    /** 캠페인 상태와 무관하게, 본인 소유 캠페인의 포스터를 돌려준다. */
    public PosterCreateResponse findForOwner(Long ownerId, Long campaignId) {
        campaignRepository.findById(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        Poster poster = posterRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.POSTER_NOT_SAVED));
        return new PosterCreateResponse(
                poster.getId(), campaignId, poster.getTemplateId(), poster.getTitle(), poster.getHtmlContent());
    }

    /*
     * 캠페인이 없는 것, DRAFT·SCHEDULED라 비공개인 것, 포스터가 없는 것 세 실패를 하나의
     * POSTER_UNAVAILABLE로 묶는다. 존재하지만 비공개인 캠페인 ID를 외부에서 구별하지 못하게
     * 한다(poster-api-design.md "사용자용 포스터 조회").
     */
    public PosterResponse findForMember(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .filter(found -> PUBLIC_STATUSES.contains(found.getStatus()))
                .orElseThrow(() -> new CampaignException(PosterErrorCode.POSTER_UNAVAILABLE));
        Poster poster = posterRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.POSTER_UNAVAILABLE));
        return new PosterResponse(campaignId, campaign.getStatus(), poster.getHtmlContent());
    }
}
