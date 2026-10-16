package com.launchcatch.adserving.impression.controller;

import com.launchcatch.adserving.impression.dto.ImpressionRequest;
import com.launchcatch.adserving.impression.dto.ImpressionResponse;
import com.launchcatch.adserving.impression.service.ImpressionService;
import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(name = "피드", description = "스와이프 피드 API")
public class ImpressionController {

    private final ImpressionService impressionService;

    /*
     * 카드가 맨 위 카드로 보인 순간 1건을 받는다.
     * 202 다. 서버는 처리를 끝내고 판정을 돌려주지만 클라이언트는 화면을 벗어나며 보낸 요청의 응답을 받지 못할 수 있어 응답에 기대지 않는다(FR-097).
     */
    @PostMapping("/impressions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "카드 표시 이벤트 수집", description = "같은 사용자가 같은 serveId 를 다시 보내면 새로 기록하지 않고 처음 판정을 돌려준다. 무효 노출도 오류가 아니라 202 다.")
    @ApiResponse(responseCode = "202", description = "노출의 유효 여부와 무효 사유를 반환한다.", useReturnTypeSchema = true)
    public ResponseEnvelope<ImpressionResponse> record(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody ImpressionRequest request) {
        return ResponseEnvelope.success(impressionService.record(user.getId(), request));
    }
}
