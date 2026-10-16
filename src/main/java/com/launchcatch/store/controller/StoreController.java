package com.launchcatch.store.controller;

import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.store.dto.StoreListItemResponse;
import com.launchcatch.store.dto.StoreListRequest;
import com.launchcatch.store.service.StoreListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class StoreController {
    private final StoreListService stores;

    @GetMapping("/v1/stores")
    ResponseEnvelope<CursorPageResponse<StoreListItemResponse>> find(@Valid @ModelAttribute StoreListRequest request) {
        return ResponseEnvelope.success(stores.find(request));
    }
}
