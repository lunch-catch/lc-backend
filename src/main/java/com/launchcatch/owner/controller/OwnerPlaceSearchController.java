package com.launchcatch.owner.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.PlaceSearchRequest;
import com.launchcatch.owner.dto.PlaceSearchResponse;
import com.launchcatch.owner.service.OwnerPlaceSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/owner/kakao-places")
@RequiredArgsConstructor
public class OwnerPlaceSearchController {
    private final OwnerPlaceSearchService service;

    @GetMapping
    public ResponseEnvelope<PlaceSearchResponse> search(
            @AuthenticationPrincipal CustomUserDetails user,
            @Valid @ModelAttribute PlaceSearchRequest request) {
        return ResponseEnvelope.success(service.search(user.getId(), request.query()));
    }
}
