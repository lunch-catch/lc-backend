package com.lunchcatch.owner.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.owner.dto.OwnerTermsAgreementRequest;
import com.lunchcatch.owner.dto.OwnerTermsAgreementResponse;
import com.lunchcatch.owner.service.OwnerTermsSubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerTermsAgreementController {
    private final OwnerTermsSubmissionService service;

    @PutMapping("/v1/owner/terms-agreements")
    public ResponseEntity<ResponseEnvelope<OwnerTermsAgreementResponse>> agree(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody(required = false) OwnerTermsAgreementRequest request) {
        return ResponseEntity.ok()
                .body(ResponseEnvelope.success(service.submit(principal.getId())));
    }
}