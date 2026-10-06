package com.launchcatch.ops.scheduler;

/*
 * 자동 재실행 사이의 대기다 (배치 운영 문서 2장).
 *
 * 인터페이스로 두는 이유는 테스트다. 30초와 1분을 실제로 기다리면 그 검사를 아무도 돌리지 않게
 * 된다. 대기 정책과 대기 행위를 한곳에 두어 스케줄러는 몇 번째 시도인지만 알려 준다.
 */
public interface RetryBackoff {

    /**
     * 다음 시도까지 기다린다. attempt 는 1부터다.
     *
     * @return 기다렸으면 true. 중단 신호를 받았으면 false 이고 그때는 더 돌리지 않는다
     */
    boolean pause(int attempt);
}
