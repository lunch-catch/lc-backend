package com.launchcatch.member.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.member.dto.MemberOnboardingRequest;
import com.launchcatch.member.dto.MemberResponse;
import com.launchcatch.member.dto.MemberUpdateRequest;
import com.launchcatch.member.service.MemberProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/members/me")
@RequiredArgsConstructor
public class MemberController {

    private final MemberProfileService memberProfileService;

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
}
