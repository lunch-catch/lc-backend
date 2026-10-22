package com.launchcatch.member.controller;

import com.launchcatch.global.logging.PiiMasker;
import com.launchcatch.member.service.MemberWithdrawalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "카카오 연결 해제", description = "카카오 서버가 호출하는 연결 해제 웹훅입니다.")
public class KakaoUnlinkWebhookController {

    private final MemberWithdrawalService memberWithdrawalService;

    @Value("${kakao.app-id:}")
    private String appId;

    @Value("${kakao.admin-key:}")
    private String adminKey;

    @RequestMapping(value = "/webhook/kakao/unlink", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "카카오 연결 해제 통보", description = "카카오 관리자 키와 앱 식별자를 검증한 뒤 최초 가입 계정의 탈퇴를 수행합니다. 재가입 계정은 과거 통보와 구분할 수 없어 계정을 유지하고 경보로 남깁니다. 진행 중인 연결 해제 작업은 삭제하지 않습니다. 검증 실패와 내부 처리 실패에도 200을 반환합니다.")
    @ApiResponse(responseCode = "200", description = "통보 수신. 실패 여부와 관계없이 본문 없이 응답합니다.", content = @Content)
    public ResponseEntity<Void> handleUnlink(
            @Parameter(description = "카카오 관리자 키 인증 헤더. 형식은 KakaoAK {관리자 키}입니다.")
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Parameter(description = "카카오 앱 식별자입니다.", example = "123456")
            @RequestParam(value = "app_id", required = false) String receivedAppId,
            @Parameter(description = "연결 해제한 카카오 회원번호입니다.", example = "1234567890")
            @RequestParam(value = "user_id", required = false) String providerUserId,
            @Parameter(description = "카카오가 전달하는 연결 해제 요청 출처입니다.")
            @RequestParam(value = "referrer_type", required = false) String referrerType
    ) {
        if (appId.isBlank() || adminKey.isBlank()
                || !appId.equals(receivedAppId) || !matchesAdminKey(authorization)) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_REJECTED providerUserId={} reason=AUTH_MISMATCH",
                    PiiMasker.maskProviderId(providerUserId));
            return ResponseEntity.ok().build();
        }
        if (providerUserId == null || providerUserId.isBlank()) {
            log.warn("event=KAKAO_UNLINK_WEBHOOK_FAILED reason=USER_ID_MISSING");
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
