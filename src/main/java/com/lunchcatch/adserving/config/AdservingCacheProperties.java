package com.lunchcatch.adserving.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 광고 서빙이 앱 안에 두는 캐시의 조정 값. 키는 {@code adserving.cache.*} 이다.
 *
 * 값을 고른 이유는 {@code application.yml} 의 같은 자리가 갖는다.
 *
 * @param candidateMaxAge 후보 캐시({@code CandidateCache})를 다시 읽기까지의 시간
 * @param memberInfoTtl   회원 정보 캐시({@code MemberInfoCache})의 보관 시간
 */
@ConfigurationProperties(prefix = "adserving.cache")
public record AdservingCacheProperties(
        @DefaultValue("5s") Duration candidateMaxAge,
        @DefaultValue("30s") Duration memberInfoTtl
) {

    public AdservingCacheProperties {
        TuningChecks.positive("candidate-max-age", candidateMaxAge);
        TuningChecks.positive("member-info-ttl", memberInfoTtl);
    }
}
