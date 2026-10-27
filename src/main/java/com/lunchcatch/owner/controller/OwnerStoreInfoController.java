package com.lunchcatch.owner.controller;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.global.response.ResponseEnvelope;
import com.lunchcatch.owner.dto.StoreInfoRequest;
import com.lunchcatch.owner.dto.StoreInfoResponse;
import com.lunchcatch.owner.service.OwnerStoreInfoService;
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
