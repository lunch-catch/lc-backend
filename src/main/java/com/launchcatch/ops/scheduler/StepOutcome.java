package com.launchcatch.ops.scheduler;

/*
 * 한 단계를 시작하려 한 결과다.
 *
 * "시작했다" 와 "이미 끝났다" 를 가른다. 이어받은 묶음은 SUCCESS 가 아닌 첫 단계부터 다시
 * 도는데, 끝난 단계를 건너뛰는 것과 막힌 단계에서 멈추는 것은 전혀 다른 일이라 한 값으로
 * 묶지 않는다.
 */
public enum StepOutcome {

    /** 이 서버가 그 단계를 들고 있다. 실행해도 된다. */
    STARTED,

    /** 이미 성공한 단계다. 건너뛴다. */
    ALREADY_DONE,

    /** 실패로 닫힌 단계이거나 다른 서버가 들고 있다. 묶음을 더 진행하지 않는다. */
    BLOCKED
}
