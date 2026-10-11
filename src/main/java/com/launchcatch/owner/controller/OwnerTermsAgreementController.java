package com.launchcatch.owner.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.OwnerTermsAgreementRequest;
import com.launchcatch.owner.dto.OwnerTermsAgreementResponse;
import com.launchcatch.owner.service.OwnerTermsSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerTermsAgreementController {
    private final OwnerTermsSubmissionService service;

    @PostMapping("/v1/owner/terms-agreements")
    public ResponseEntity<ResponseEnvelope<OwnerTermsAgreementResponse>> agree(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody OwnerTermsAgreementRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseEnvelope.success(service.submit(principal.getId(), request)));
    }
}