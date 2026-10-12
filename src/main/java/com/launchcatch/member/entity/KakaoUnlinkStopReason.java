package com.launchcatch.member.entity;

/*
 * 카카오 unlink 재시도를 멈춘 이유다. kakao_unlink_failure.stop_reason 으로 들어간다.
 *
 * 둘을 가르는 것은 운영 대응이 다르기 때문이다. EXHAUSTED 는 우리가 더 해 볼 것이 없는
 * 상태라 사람이 카카오 상태를 보고 수동으로 다시 돌릴 거리이고, REJECTED 는 카카오가
 * "이 대상은 해제할 것이 없다" 고 답한 것이라 그 자체로 끝난 상태다.
 *
 * 한도 소진을 attempt_count 로만 표현하면 상수를 올리는 순간 예전에 포기한 행이 전부
 * 되살아난다. 그래서 종료는 값으로 남긴다.
 */
public enum KakaoUnlinkStopReason {

    /** 카카오가 영구적으로 거부했다. 재시도해도 같은 답이 온다. */
    REJECTED,

    /** 재시도 한도를 모두 썼다. */
    EXHAUSTED
}
