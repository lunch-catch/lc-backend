package com.launchcatch.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * 스케줄러는 batch 프로필에서만 켠다.
 * 앱 인스턴스에서 켜지면 앱 대수만큼 중복 실행된다. 멱등은 (작업명, 영업일) UNIQUE 로 막지만
 * 애초에 한 대에서만 도는 편이 맞다(규칙 4).
 * 배치 전용 인스턴스만 prod,batch 로 뜨고 앱 인스턴스는 prod 로 뜬다.
 */
@Configuration
@Profile("batch")
@EnableScheduling
public class SchedulingConfig {
}
