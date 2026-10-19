package com.launchcatch.campaign.poster.controller;

import com.launchcatch.campaign.poster.dto.PosterResponse;
import com.launchcatch.campaign.poster.service.PosterQueryService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/campaigns/{campaignId}/poster")
@RequiredArgsConstructor
@Tag(name = "사용자 포스터", description = "가게 상세 화면에 표시할 포스터를 조회하는 API입니다.")
public class PosterController {

    private final PosterQueryService posterQueryService;

    @GetMapping
    @Operation(
            summary = "사용자용 포스터 조회",
            description = "저장된 포스터 HTML을 조회합니다. 캠페인이 ACTIVE, PAUSED, ENDED일 때만 조회할 수 있습니다."
    )
    public ResponseEnvelope<PosterResponse> get(@PathVariable Long campaignId) {
        return ResponseEnvelope.success(posterQueryService.findForMember(campaignId));
    }
}
