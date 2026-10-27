package com.lunchcatch.campaign.poster.controller;

import com.lunchcatch.campaign.poster.dto.PosterResponse;
import com.lunchcatch.campaign.poster.service.PosterQueryService;
import com.lunchcatch.global.response.ResponseEnvelope;
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
     * 공개된 캠페인(ACTIVE, PAUSED, ENDED)의 포스터는 바뀌지 않는다. 포스터는 DRAFT 일 때만 고칠 수 있고, DRAFT 로
     * 돌아가는 길은 아직 비공개인 SCHEDULED 의 취소뿐이다. 그래서 같은 주소의 응답은 항상 같고, 브라우저가 하루 동안
     * 다시 받지 않게 한다(#940). 이 API 는 MEMBER 인증을 요구하므로 공유 캐시에 남지 않게 private 으로 둔다.
     * 실패 응답에는 이 헤더가 붙지 않는다.
     */
    private static final Duration CACHE_MAX_AGE = Duration.ofDays(1);

    private final PosterQueryService posterQueryService;

    @GetMapping
    @Operation(
            summary = "사용자용 포스터 조회",
            description = "저장된 포스터 HTML을 조회합니다. 캠페인이 ACTIVE, PAUSED, ENDED일 때만 조회할 수 있습니다."
    )
    public ResponseEntity<ResponseEnvelope<PosterResponse>> get(@PathVariable Long campaignId) {
        PosterResponse response = posterQueryService.findForMember(campaignId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePrivate().immutable())
                .body(ResponseEnvelope.success(response));
    }
}
