package com.launchcatch.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * 시간에 의존하는 로직(JwtTokenProvider의 발급/만료 시각 등)이 System.currentTimeMillis()를
 * 직접 부르지 않고 이 Clock을 주입받게 하기 위한 빈 등록. 테스트는 Clock.fixed(...)로 "지금"을
 * 원하는 시점에 고정해서 만료 전/후 같은 시간 경계 케이스를 결정적으로 재현할 수 있다.
 *
 * 시간대를 Asia/Seoul 로 못 박는다. 영업일(business_date)과 모든 시각은 애플리케이션이
 * 계산해 넣고 관계형 DB 의 날짜 함수(CURDATE, NOW)는 쓰지 않는다.
 * systemDefaultZone() 으로 두면 개발자 기계와 배포 환경의 기본 시간대에 따라 영업일 경계가
 * 갈린다. 00:00 배치와 10:00~12:59 서빙 시간대 판정이 전부 이 값에 달려 있어 고정이 맞다.
 */
@Configuration
public class ClockConfig {

    /** 영업일과 모든 시각의 기준 시간대. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
