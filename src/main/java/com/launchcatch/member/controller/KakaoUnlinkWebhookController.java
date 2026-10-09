package com.launchcatch.member.controller;

import com.launchcatch.global.logging.PiiMasker;
import com.launchcatch.member.service.MemberWithdrawalService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class KakaoUnlinkWebhookController {

    private final MemberWithdrawalService memberWithdrawalService;

    @Value("${kakao.app-id:}")
    private String appId;

    @Value("${kakao.admin-key:}")
    private String adminKey;

    @RequestMapping(value = "/webhook/kakao/unlink", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> handleUnlink(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(value = "app_id", required = false) String receivedAppId,
            @RequestParam(value = "user_id", required = false) String providerUserId,
            @RequestParam(value = "referrer_type", required = false) String referrerType
    ) {
        if (appId.isBlank() || adminKey.isBlank()
                || !appId.equals(receivedAppId) || !matchesAdminKey(authorization)) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_REJECTED providerUserId={} referrerType={}",
                    PiiMasker.maskProviderId(providerUserId), referrerType);
            return ResponseEntity.ok().build();
        }
        if (providerUserId == null || providerUserId.isBlank()) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_MISSING_USER_ID referrerType={}", referrerType);
            return ResponseEntity.ok().build();
        }
        try {
            memberWithdrawalService.withdrawByKakaoWebhook(providerUserId);
        } catch (RuntimeException e) {
            /*
             * 명세(member.md)에 따라 처리에 실패해도 200 으로 답하고 로그만 남긴다.
             * 실패를 응답 코드로 드러내면 카카오의 재전송을 유발한다.
             */
            log.error("event=KAKAO_UNLINK_WEBHOOK_PROCESSING_FAILED providerUserId={}",
                    PiiMasker.maskProviderId(providerUserId), e);
        }
        return ResponseEntity.ok().build();
    }

    private boolean matchesAdminKey(String authorization) {
        if (authorization == null) {
            return false;
        }
        byte[] expected = ("KakaoAK " + adminKey).getBytes(StandardCharsets.UTF_8);
        byte[] received = authorization.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, received);
    }
}
