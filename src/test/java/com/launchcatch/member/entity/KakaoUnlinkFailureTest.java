package com.launchcatch.member.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoUnlinkFailureTest {

    private static final int MAX_ATTEMPTS = 5;

    @Test
    @DisplayName("unlink 대기 행은 카카오 회원번호를 사본으로 보관한다")
    void unlink_대기_행은_카카오_회원번호를_사본으로_보관한다() {
        Member member = Member.create("kakao-1", "닉네임", null);

        KakaoUnlinkFailure failure = KakaoUnlinkFailure.create(member);

        assertThat(failure.getProviderUserId()).isEqualTo("kakao-1");
        assertThat(failure.getAttemptCount()).isZero();
        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.getStopReason()).isNull();
    }

    @Test
    @DisplayName("회원 없이 unlink 대기 행을 만들지 않는다")
    void 회원_없이_unlink_대기_행을_만들지_않는다() {
        assertThatThrownBy(() -> KakaoUnlinkFailure.create(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한도 전의 실패는 횟수만 올린다")
    void 한도_전의_실패는_횟수만_올린다() {
        KakaoUnlinkFailure failure = failure();

        int attempts = failure.recordRetryFailure(MAX_ATTEMPTS);

        assertThat(attempts).isOne();
        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.getStopReason()).isNull();
    }

    @Test
    @DisplayName("한도에 닿으면 소진으로 닫는다")
    void 한도에_닿으면_소진으로_닫는다() {
        KakaoUnlinkFailure failure = failure();

        failure.recordRetryFailure(1);

        assertThat(failure.isResolved()).isTrue();
        assertThat(failure.getStopReason()).isEqualTo(KakaoUnlinkStopReason.EXHAUSTED);
    }

    /*
     * 영구 거부가 횟수를 건드리면 "다섯 번 시도했다" 와 "한 번 만에 거부당했다" 가 같은 값이
     * 되어 운영에서 멈춘 이유를 가릴 수 없다.
     */
    @Test
    @DisplayName("영구 거부는 재시도 횟수를 건드리지 않는다")
    void 영구_거부는_재시도_횟수를_건드리지_않는다() {
        KakaoUnlinkFailure failure = failure();

        failure.rejectPermanently();

        assertThat(failure.getAttemptCount()).isZero();
        assertThat(failure.isResolved()).isTrue();
        assertThat(failure.getStopReason()).isEqualTo(KakaoUnlinkStopReason.REJECTED);
    }

    /*
     * 재가입이 대기 행을 지우지 못한 채로 같은 회원이 다시 탈퇴하면, UNIQUE(member_id) 때문에
     * 새 행을 넣을 수 없다. 닫힌 행을 그대로 두면 이번 탈퇴의 해제가 한 번도 시도되지 않는다.
     */
    @Test
    @DisplayName("다시 열면 닫힌 행이 처음 상태로 돌아간다")
    void 다시_열면_닫힌_행이_처음_상태로_돌아간다() {
        KakaoUnlinkFailure failure = failure();
        failure.rejectPermanently();

        failure.reopen();

        assertThat(failure.getAttemptCount()).isZero();
        assertThat(failure.isResolved()).isFalse();
        assertThat(failure.getStopReason()).isNull();
    }

    private KakaoUnlinkFailure failure() {
        return KakaoUnlinkFailure.create(Member.create("kakao-1", "닉네임", null));
    }
}
