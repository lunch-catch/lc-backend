package com.launchcatch.owner.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.owner.dto.StoreInfoRequest;
import com.launchcatch.owner.dto.StoreInfoResponse;
import com.launchcatch.owner.service.OwnerStoreInfoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OwnerStoreInfoController {
    private final OwnerStoreInfoService stores;

    @PutMapping("/v1/owner/store/basic")
    public ResponseEnvelope<StoreInfoResponse> save(@AuthenticationPrincipal CustomUserDetails user,
                                                  @Valid @RequestBody StoreInfoRequest request) {
        return ResponseEnvelope.success(stores.save(user.getId(), request));
    }
}
