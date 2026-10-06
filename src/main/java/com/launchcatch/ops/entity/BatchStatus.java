package com.launchcatch.ops.entity;

/** 배치 실행 상태. batch_execution_log 의 chk_batch_status 가 이 셋만 받는다. */
public enum BatchStatus {

    RUNNING,
    SUCCESS,
    FAILED
}
