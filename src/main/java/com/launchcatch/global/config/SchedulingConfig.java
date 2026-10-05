package com.launchcatch.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * 스케줄러는 배치 서버에서만 켠다.
 * 앱 인스턴스에서 켜지면 앱 대수만큼 중복 실행된다. 멱등은 (작업명, 영업일) UNIQUE 로 막지만
 * 애초에 켜지지 않는 편이 맞다(규칙 6).
 *
 * 프로필이 아니라 설정값으로 가른다. 같은 jar 를 배치 역할로 띄우므로 프로필로 갈라도 되지만,
 * 장애 대응 중에 자동 실행만 멈추려면 프로필을 바꿔 재배포해야 한다. 설정값이면 환경변수
 * LAUNCHCATCH_SCHEDULER_ENABLED=false 로 끌 수 있다 (배치 운영 문서 1장).
 *
 * 값을 켜는 곳은 application-batch.yml 뿐이다. 기본 프로필에는 없어서 앱 인스턴스는 자동으로
 * 꺼진 상태다.
 */
@Configuration
@ConditionalOnProperty(name = "launchcatch.scheduler.enabled", havingValue = "true")
@EnableScheduling
public class SchedulingConfig {
}
