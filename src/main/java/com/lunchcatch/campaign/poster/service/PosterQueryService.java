package com.lunchcatch.campaign.poster.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lunchcatch.campaign.contract.CampaignStatus;
import com.lunchcatch.campaign.entity.Campaign;
import com.lunchcatch.campaign.exception.CampaignErrorCode;
import com.lunchcatch.campaign.exception.CampaignException;
import com.lunchcatch.campaign.exception.PosterErrorCode;
import com.lunchcatch.campaign.poster.PosterSlots;
import com.lunchcatch.campaign.poster.dto.PosterCreateResponse;
import com.lunchcatch.campaign.poster.dto.PosterResponse;
import com.lunchcatch.campaign.poster.entity.Poster;
import com.lunchcatch.campaign.poster.repository.PosterRepository;
import com.lunchcatch.campaign.repository.CampaignRepository;
import java.util.Optional;
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

    /*
     * 공개된 포스터 HTML 캐시가 쓰는 메모리의 상한이다. 항목 수가 아니라 HTML 글자 수의 합으로 건다. 포스터 HTML 은 TEXT
     * 컬럼이라 크기가 제각각이어서, 항목 수로만 걸면 큰 포스터가 몰릴 때 메모리를 얼마나 쓸지 알 수 없다. 글자는 한글이
     * 있으면 2바이트로 잡혀 이 값은 최대 20MB 안팎이다. 넘으면 오래 쓰지 않은 항목부터 밀려나고, 밀려난 포스터는 다음
     * 조회에서 DB 에서 다시 읽는다. 상한보다 큰 포스터 하나는 담지 않고 매번 DB 에서 읽는다.
     */
    private static final long PUBLIC_POSTER_CACHE_MAX_CHARS = 10_000_000L;

    private final CampaignRepository campaignRepository;
    private final PosterRepository posterRepository;

    /*
     * 공개로 확인된 포스터의 HTML 만 campaignId 를 키로 서버 메모리(Caffeine)에 둔다(#940). 비공개, 없는 캠페인, 포스터
     * 없음은 담지 않아 나중에 공개되거나 포스터가 생기면 그대로 반영된다.
     *
     * 지우거나 만료시키지 않는 근거: 공개된 캠페인(ACTIVE, PAUSED, ENDED)의 포스터는 바뀌지 않는다. 포스터를 쓰는 곳은
     * 생성, 덮어쓰기, 텍스트 수정(PosterUploadService, PosterEditService)뿐이고 모두 캠페인이 DRAFT 일 때만 동작한다.
     * 공개 상태는 DRAFT 로 돌아가지 않고, DRAFT 로 돌아가는 길인 취소(Campaign.cancel)는 아직 비공개인 SCHEDULED 에서만
     * 된다. 그래서 한 번 공개로 확인한 포스터는 다시 바뀌거나 비공개가 되지 않는다. 공개된 포스터를 고치는 기능이나
     * 공개를 거두는 기능이 생기면 이 캐시를 함께 무효화해야 한다. 서버마다 따로 갖지만 내용이 변하지 않아 서버끼리
     * 어긋날 일이 없다.
     */
    private final Cache<Long, String> publicPosterHtml = Caffeine.newBuilder()
            .maximumWeight(PUBLIC_POSTER_CACHE_MAX_CHARS)
            .weigher((Long campaignId, String html) -> html.length())
            .build();

    /** 캠페인 상태와 무관하게, 본인 소유 캠페인의 포스터를 돌려준다. */
    public PosterCreateResponse findForOwner(Long ownerId, Long campaignId) {
        campaignRepository.findById(campaignId)
                .filter(found -> found.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new CampaignException(CampaignErrorCode.CAMPAIGN_NOT_OWNED));
        Poster poster = posterRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new CampaignException(PosterErrorCode.POSTER_NOT_SAVED));
        return new PosterCreateResponse(
                poster.getId(), campaignId, poster.getTemplateId(), poster.getTitle(), poster.getHtmlContent(),
                PosterSlots.view(poster.getSlotValues(), poster.getHtmlContent()));
    }

    /*
     * 캠페인이 없는 것, DRAFT·SCHEDULED라 비공개인 것, 포스터가 없는 것 세 실패를 하나의
     * POSTER_UNAVAILABLE로 묶는다. 존재하지만 비공개인 캠페인 ID를 외부에서 구별하지 못하게
     * 한다(poster-api-design.md "사용자용 포스터 조회").
     *
     * 캠페인 조회와 포스터 조회를 판정 전에 둘 다 끝낸다. 캠페인 조회에서 바로 실패를
     * 던지면(filter().orElseThrow()), 비공개·존재하지 않는 캠페인은 조회 1번으로 끝나고
     * 공개 캠페인에 포스터만 없는 경우만 조회 2번이 걸려 응답 시간으로 공개 여부를 가를 수
     * 있다(#495 자체 리뷰로 찾음). 실패 원인과 무관하게 항상 같은 쿼리 횟수를 치르게 한다.
     */
    public PosterResponse findForMember(Long campaignId) {
        String cached = publicPosterHtml.getIfPresent(campaignId);
        if (cached != null) {
            return new PosterResponse(campaignId, cached);
        }
        Optional<Campaign> campaign = campaignRepository.findById(campaignId);
        Optional<Poster> poster = posterRepository.findByCampaignId(campaignId);
        boolean visible = campaign.map(found -> PUBLIC_STATUSES.contains(found.getStatus())).orElse(false);
        if (!visible || poster.isEmpty()) {
            throw new CampaignException(PosterErrorCode.POSTER_UNAVAILABLE);
        }
        String html = poster.get().getHtmlContent();
        publicPosterHtml.put(campaignId, html);
        return new PosterResponse(campaignId, html);
    }
}
