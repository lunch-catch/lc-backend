package com.lunchcatch.billing.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 그날 계획 스냅샷 캐시 설정. 키는 {@code billing.plan-snapshot-cache.*} 이다.
 *
 * 값은 측정하지 않고 보수적으로 두었다.
 *
 * @param maximumSize      최대 항목 수. 하루에 도는 캠페인 수보다 넉넉하게 둔다
 * @param expireAfterWrite 적재 뒤 만료. 영업일이 키에 들어 있어 하루 안팎이면 충분하다
 */
@ConfigurationProperties(prefix = "billing.plan-snapshot-cache")
public record PlanSnapshotCacheProperties(
        @DefaultValue("100000") long maximumSize,
        @DefaultValue("24h") Duration expireAfterWrite
) {

    public PlanSnapshotCacheProperties {
        Objects.requireNonNull(expireAfterWrite, "expire-after-write 가 필요하다");
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximum-size 는 0 보다 커야 한다");
        }
        if (expireAfterWrite.isZero() || expireAfterWrite.isNegative()) {
            throw new IllegalArgumentException("expire-after-write 는 0 보다 커야 한다");
        }
    }
}
