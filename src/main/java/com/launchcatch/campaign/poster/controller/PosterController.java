package com.launchcatch.campaign.poster.controller;

import com.launchcatch.campaign.poster.dto.PosterResponse;
import com.launchcatch.campaign.poster.service.PosterQueryService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/campaigns/{campaignId}/poster")
@RequiredArgsConstructor
@Tag(name = "사용자 포스터", description = "가게 상세 화면에 표시할 포스터를 조회하는 API입니다.")
public class PosterController {

    /*
     * 점주가 다시 만들기 전까지 바뀌지 않는 공개 데이터라 짧게라도 캐시한다. 가게 상세
     * 화면이 부르는 조회라 트래픽이 점주용 API보다 높을 수 있다(#495 자체 리뷰로 찾음).
     */
    private static final Duration CACHE_MAX_AGE = Duration.ofSeconds(60);

    private final PosterQueryService posterQueryService;

    @GetMapping
    @Operation(
            summary = "사용자용 포스터 조회",
            description = "저장된 포스터 HTML을 조회합니다. 캠페인이 ACTIVE, PAUSED, ENDED일 때만 조회할 수 있습니다."
    )
    public ResponseEntity<ResponseEnvelope<PosterResponse>> get(@PathVariable Long campaignId) {
        PosterResponse response = posterQueryService.findForMember(campaignId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePublic())
                .body(ResponseEnvelope.success(response));
    }
}
