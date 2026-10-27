package com.lunchcatch.owner.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.owner.dto.OwnerProfileResponse;
import com.lunchcatch.owner.service.OwnerProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/me")
@RequiredArgsConstructor
public class OwnerProfileController {
    private final OwnerProfileService ownerProfileService;

    @GetMapping
    public ResponseEnvelope<OwnerProfileResponse> getMyProfile(@AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(ownerProfileService.getMyProfile(user.getId()));
    }
}