package com.launchcatch.adserving.feed.controller;

import com.launchcatch.adserving.feed.dto.FeedFilterResponse;
import com.launchcatch.adserving.feed.dto.FeedFilterSaveRequest;
import com.launchcatch.adserving.feed.service.FeedFilterService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "피드", description = "스와이프 피드 API")
public class FeedFilterController {

    private final FeedFilterService feedFilterService;

    // 내 피드 필터를 조회한다. 사용자당 하나뿐이라 경로가 단수다.
    @GetMapping("/feed-filter")
    @Operation(summary = "내 피드 필터 조회", description = "저장한 적이 없으면 categories 는 빈 배열(전체 업종), sort 는 DISTANCE 다.")
    @ApiResponse(responseCode = "200", description = "고른 업종과 정렬을 반환한다.", useReturnTypeSchema = true)
    public ResponseEnvelope<FeedFilterResponse> getFilter(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(feedFilterService.getFilter(user.getId()));
    }

    // 필터를 통째로 바꾼다. 다음 피드 요청부터 적용된다.
    @PutMapping("/feed-filter")
    @Operation(summary = "피드 필터 저장", description = "categories 의 빈 배열은 전체 업종이다. 없는 업종 코드나 허용되지 않은 정렬 값이면 400 COMMON-002 다.")
    @ApiResponse(responseCode = "200", description = "저장한 필터를 조회와 같은 모양으로 반환한다.", useReturnTypeSchema = true)
    public ResponseEnvelope<FeedFilterResponse> saveFilter(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody FeedFilterSaveRequest request) {
        return ResponseEnvelope.success(feedFilterService.saveFilter(user.getId(), request));
    }
}
