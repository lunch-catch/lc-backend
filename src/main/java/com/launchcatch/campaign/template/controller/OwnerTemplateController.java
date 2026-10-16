package com.launchcatch.campaign.template.controller;

import com.launchcatch.campaign.template.dto.OwnerTemplateListItemResponse;
import com.launchcatch.campaign.template.service.TemplateQueryService;
import com.launchcatch.global.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/templates")
@RequiredArgsConstructor
@Tag(name = "점주 템플릿", description = "점주가 포스터 생성 시 고를 템플릿을 조회하는 API입니다.")
public class OwnerTemplateController {

    private final TemplateQueryService templateQueryService;

    @GetMapping
    @Operation(
            summary = "점주 템플릿 목록 조회",
            description = "점주가 포스터 생성 시 고를 수 있는 템플릿을 생성일 최신순으로 조회합니다. "
                    + "게시되고 활성인 템플릿만 대상입니다."
    )
    @ApiResponse(responseCode = "200", description = "점주 템플릿 목록 조회 성공.")
    public ResponseEntity<ResponseEnvelope<List<OwnerTemplateListItemResponse>>> list() {
        return ResponseEntity.ok(ResponseEnvelope.success(templateQueryService.listForOwner()));
    }
}
