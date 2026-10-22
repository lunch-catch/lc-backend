package com.launchcatch.owner.controller;

import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.OwnerSignupRequest;
import com.launchcatch.owner.dto.OwnerSignupResponse;
import com.launchcatch.owner.service.OwnerSignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerSignupController {
    private final OwnerSignupService ownerSignupService;

    @PostMapping("/v1/owners")
    public ResponseEntity<ResponseEnvelope<OwnerSignupResponse>> signup(
            @Valid @RequestBody OwnerSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseEnvelope.success(ownerSignupService.signup(request)));
    }
}