package com.launchcatch.global.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/*
 * 몇 초마다 도는 작업에 붙인다. SchedulerLoggingAspect 가 시작과 끝을 INFO 대신 DEBUG 로 남긴다.
 * 실패는 그대로 ERROR 로 남고 마지막 성공 시각 지표도 그대로 낸다. 짧은 주기의 정상 실행 로그가 다른 배치 로그를 묻지 않게 한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface QuietSchedulerLog {
}
