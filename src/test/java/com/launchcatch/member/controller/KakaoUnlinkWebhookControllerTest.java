package com.launchcatch.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.launchcatch.member.service.MemberWithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class KakaoUnlinkWebhookControllerTest {

    @Mock MemberWithdrawalService memberWithdrawalService;

    private KakaoUnlinkWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new KakaoUnlinkWebhookController(memberWithdrawalService);
        ReflectionTestUtils.setField(controller, "appId", "12345");
        ReflectionTestUtils.setField(controller, "adminKey", "secret");
    }

    @Test
    void 앱과_관리자_키가_맞으면_웹훅_탈퇴를_처리한다() {
        var response = controller.handleUnlink("KakaoAK secret", "12345", "kakao-1", "ACCOUNT_DELETE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(memberWithdrawalService).withdrawByKakaoWebhook("kakao-1");
    }

    @Test
    void 검증값이_다르면_처리하지_않고_200을_반환한다() {
        var response = controller.handleUnlink("KakaoAK wrong", "12345", "kakao-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(memberWithdrawalService, never()).withdrawByKakaoWebhook("kakao-1");
    }

    @Test
    void 사용자_번호가_없으면_처리하지_않고_200을_반환한다() {
        var response = controller.handleUnlink("KakaoAK secret", "12345", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(memberWithdrawalService, never()).withdrawByKakaoWebhook(null);
    }

    @Test
    void 내부_처리가_실패하면_500을_반환해_재전송에_맡긴다() {
        org.mockito.Mockito.doThrow(new RuntimeException("db"))
                .when(memberWithdrawalService).withdrawByKakaoWebhook("kakao-1");

        var response = controller.handleUnlink("KakaoAK secret", "12345", "kakao-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
