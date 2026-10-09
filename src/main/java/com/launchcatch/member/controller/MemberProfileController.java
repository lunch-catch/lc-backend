package com.launchcatch.member.controller;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.global.response.ResponseEnvelope;
import com.launchcatch.member.dto.MemberLocationRequest;
import com.launchcatch.member.dto.MemberLocationResponse;
import com.launchcatch.member.service.MemberLocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/member-profiles/location")
@RequiredArgsConstructor
public class MemberProfileController {

    private final MemberLocationService memberLocationService;

    @PutMapping
    public ResponseEnvelope<MemberLocationResponse> save(@AuthenticationPrincipal CustomUserDetails user,
                                                          @Valid @RequestBody MemberLocationRequest request) {
        return ResponseEnvelope.success(memberLocationService.save(user.getId(), request));
    }

    @GetMapping
    public ResponseEnvelope<MemberLocationResponse> get(@AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEnvelope.success(memberLocationService.get(user.getId()));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CustomUserDetails user) {
        memberLocationService.delete(user.getId());
        return ResponseEntity.noContent().build();
    }
}
