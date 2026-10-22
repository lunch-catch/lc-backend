package com.launchcatch.owner.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.OwnerTutorialResponse;
import com.launchcatch.owner.service.OwnerTutorialService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerTutorialController {
    private final OwnerTutorialService service;

    @PatchMapping("/v1/owner/me/tutorial")
    public ResponseEnvelope<OwnerTutorialResponse> complete(@AuthenticationPrincipal CustomUserDetails principal) {
        return ResponseEnvelope.success(service.complete(principal.getId()));
    }
}