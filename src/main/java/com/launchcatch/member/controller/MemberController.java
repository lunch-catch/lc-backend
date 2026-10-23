package com.launchcatch.member.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.AuthCookieFactory;
import com.launchcatch.auth.Role;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.member.dto.MemberOnboardingRequest;
import com.launchcatch.member.dto.MemberResponse;
import com.launchcatch.member.dto.MemberUpdateRequest;
import com.launchcatch.member.dto.MemberWithdrawalRequest;
import com.launchcatch.member.service.MemberProfileService;
import com.launchcatch.member.service.MemberWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/members")
@RequiredArgsConstructor
@Tag(name = "회원 정보", description = "내 정보 조회·수정, 온보딩, 카카오 재인증 후 탈퇴 API입니다.")
public class MemberController {

    private final MemberProfileService memberProfileService;
    private final MemberWithdrawalService memberWithdrawalService;
    private final AuthCookieFactory authCookieFactory;

    @PutMapping("/me/onboarding")
    @Operation(summary = "회원 온보딩 완료", description = "성별, 연령대, 위치·알림 동의를 모두 입력해 온보딩을 완료합니다. 정지된 회원도 이용할 수 있습니다. 완료 후 성별과 연령대는 수정할 수 없으며, 재요청은 409입니다. 피드 이용 가능 여부는 온보딩 완료와 위치 동의가 모두 참일 때만 참입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "온보딩 완료 및 회원 정보 반환", useReturnTypeSchema = true, content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 필수 값 누락. COMMON-003: 요청 형식 오류. MEMBER-001: 이미 탈퇴한 회원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 회원 인증 필요", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 회원 권한이 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "409", description = "MEMBER-002: 이미 온보딩을 완료한 회원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEnvelope<MemberResponse> completeOnboarding(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody MemberOnboardingRequest request) {
        return ResponseEnvelope.success(memberProfileService.completeOnboarding(user.getId(), request));
    }

    @GetMapping("/me")
    @Operation(summary = "내 회원 정보 조회", description = "프로필, 동의와 동의 시각, 온보딩 완료 및 피드 이용 가능 여부를 조회합니다. 온보딩 전에는 profile이 null이며, 프로필 이미지와 동의 시각도 값이 없으면 null입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원 정보 조회 성공", useReturnTypeSchema = true, content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "MEMBER-001: 이미 탈퇴한 회원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 회원 인증 필요", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 회원 권한이 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEnvelope<MemberResponse> getMyProfile(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(memberProfileService.getMyProfile(user.getId()));
    }

    @PatchMapping("/me")
    @Operation(summary = "내 회원 정보 수정", description = "닉네임과 위치·알림 동의를 부분 수정합니다. 생략하거나 null로 보낸 필드는 유지하며 적어도 한 필드를 지정해야 합니다. 닉네임은 1~20자이며 공백만 입력할 수 없습니다. 성별, 연령대와 프로필 이미지는 수정 대상이 아닙니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정된 회원 정보 반환", useReturnTypeSchema = true, content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 수정할 필드 없음 또는 검증 실패. COMMON-003: 요청 형식 오류. MEMBER-001: 이미 탈퇴한 회원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 회원 인증 필요", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 회원 권한이 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEnvelope<MemberResponse> updateMyProfile(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody MemberUpdateRequest request) {
        return ResponseEnvelope.success(memberProfileService.updateMyProfile(user.getId(), request));
    }

    @PostMapping("/me:withdraw")
    @Operation(summary = "회원 탈퇴", description = "재인증용 카카오 인가 URL에서 받은 인가 코드와 상태 검증값으로 본인 확인 후 탈퇴합니다. 개인정보를 비식별 처리하고 토큰을 폐기하며 인증 쿠키를 만료시킵니다. 커밋 후 카카오 연결 해제를 요청하고 실패 시 재시도 대상으로 남깁니다. 선택 입력한 탈퇴 사유는 저장하지 않습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "탈퇴 완료 및 인증 쿠키 만료", content = @Content),
            @ApiResponse(responseCode = "400", description = "COMMON-002: 요청 검증 실패. COMMON-003: 요청 형식 오류. MEMBER-001: 이미 탈퇴한 회원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "401", description = "AUTH-005: 회원 인증 필요. AUTH-007: 재인증 실패 또는 다른 카카오 계정", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "403", description = "AUTH-006: 회원 권한이 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class))),
            @ApiResponse(responseCode = "503", description = "AUTH-002: 토큰 폐기 실패", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResponseEnvelope.class)))
    })
    public ResponseEntity<Void> withdraw(@Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails user,
                                         @Valid @RequestBody MemberWithdrawalRequest request) {
        memberWithdrawalService.withdraw(user.getId(), request.authorizationCode(), request.state());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie(Role.MEMBER).toString())
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie(Role.MEMBER).toString())
                .build();
    }
}
