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
@RequestMapping("/v1/members/me")
@RequiredArgsConstructor
public class MemberController {

    private final MemberProfileService memberProfileService;
    private final MemberWithdrawalService memberWithdrawalService;
    private final AuthCookieFactory authCookieFactory;

    @PutMapping("/onboarding")
    public ResponseEnvelope<MemberResponse> completeOnboarding(
            @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody MemberOnboardingRequest request) {
        return ResponseEnvelope.success(memberProfileService.completeOnboarding(user.getId(), request));
    }

    @GetMapping
    public ResponseEnvelope<MemberResponse> getMyProfile(@AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(memberProfileService.getMyProfile(user.getId()));
    }

    @PatchMapping
    public ResponseEnvelope<MemberResponse> updateMyProfile(
            @AuthenticationPrincipal CustomUserDetails user,
            @Valid @RequestBody MemberUpdateRequest request) {
        return ResponseEnvelope.success(memberProfileService.updateMyProfile(user.getId(), request));
    }

    @PostMapping(":withdraw")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal CustomUserDetails user,
                                         @Valid @RequestBody MemberWithdrawalRequest request) {
        memberWithdrawalService.withdraw(user.getId(), request.authorizationCode(), request.state());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.expiredAccessTokenCookie().toString())
                .header(HttpHeaders.SET_COOKIE, authCookieFactory.expiredRefreshTokenCookie(Role.MEMBER).toString())
                .build();
    }
}
