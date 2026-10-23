package com.launchcatch.member.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.member.dto.MemberLocationRequest;
import com.launchcatch.member.dto.MemberLocationResponse;
import com.launchcatch.member.service.MemberLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/member-profiles/location")
@RequiredArgsConstructor
@Tag(name = "회원 저장 위치", description = "회원당 대표 저장 위치 한 개를 설정·조회·삭제합니다.")
public class MemberProfileController {

    private final MemberLocationService memberLocationService;

    @PutMapping
    @Operation(summary = "대표 저장 위치 설정", description = "위도와 경도를 함께 받아 기존 위치를 대체합니다. 별칭과 도로명 주소는 선택입니다. 온보딩 전에도 저장할 수 있고, 위치 동의 여부는 저장을 거부하는 조건이 아닙니다. 현재 위치를 받지 못한 피드 조회의 대체 위치로 사용합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장된 대표 위치 반환", useReturnTypeSchema = true, content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 좌표 누락·범위 또는 문자열 길이 검증 실패. COMMON-003: 요청 형식 오류. MEMBER-001: 이미 탈퇴한 회원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 회원 인증 필요", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 회원 권한이 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEnvelope<MemberLocationResponse> save(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
                                                          @Valid @RequestBody MemberLocationRequest request) {
        return ResponseEnvelope.success(memberLocationService.save(user.getId(), request));
    }

    @GetMapping
    @Operation(summary = "대표 저장 위치 조회", description = "저장 위치가 없거나 프로필 행이 없으면 네 필드가 모두 null인 200 응답을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "대표 위치 조회 성공", useReturnTypeSchema = true, content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 회원 인증 필요", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 회원 권한이 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEnvelope<MemberLocationResponse> get(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(memberLocationService.get(user.getId()));
    }

    @DeleteMapping
    @Operation(summary = "대표 저장 위치 삭제", description = "위치 네 필드를 비우고 프로필 행과 온보딩·위치 동의 상태는 유지합니다. 프로필 또는 위치가 없어도 204를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "대표 위치 삭제 완료", content = @Content),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 회원 인증 필요", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 회원 권한이 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEntity<Void> delete(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user) {
        memberLocationService.delete(user.getId());
        return ResponseEntity.noContent().build();
    }
}
