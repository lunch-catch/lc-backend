package com.launchcatch.billing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Cache;
import com.launchcatch.billing.dto.PlanSnapshot;
import com.launchcatch.billing.dto.PlanSnapshotKey;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PlanSnapshotCachePropertiesTest {

    @Test
    void 아무것도_안_주면_시작값으로_채워진다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("billing.plan-snapshot-cache.maximum-size", "100000")));

        PlanSnapshotCacheProperties properties = binder.bind("billing.plan-snapshot-cache", PlanSnapshotCacheProperties.class).get();

        assertThat(properties.maximumSize()).isEqualTo(100_000L);
        assertThat(properties.expireAfterWrite()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void 최대_크기가_양수가_아니면_거부한다() {
        assertThatThrownBy(() -> new PlanSnapshotCacheProperties(0, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum-size");
    }

    @Test
    void 만료가_양수가_아니면_거부한다() {
        assertThatThrownBy(() -> new PlanSnapshotCacheProperties(10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expire-after-write");
        assertThatThrownBy(() -> new PlanSnapshotCacheProperties(10, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 설정으로_캐시를_만든다() {
        Cache<PlanSnapshotKey, PlanSnapshot> cache =
                new PlanSnapshotCacheConfig().planSnapshotCache(new PlanSnapshotCacheProperties(10, Duration.ofHours(1)));
        PlanSnapshotKey key = new PlanSnapshotKey(LocalDate.of(2026, 10, 14), 7L);

        cache.put(key, new PlanSnapshot(1L, 3L, 10L));

        assertThat(cache.getIfPresent(key)).isEqualTo(new PlanSnapshot(1L, 3L, 10L));
    }

    @Test
    void 시간대별_목표_캐시도_같은_설정으로_만든다() {
        Cache<Long, Map<Integer, Long>> cache =
                new PlanSnapshotCacheConfig().hourlyTargetCache(new PlanSnapshotCacheProperties(10, Duration.ofHours(1)));

        cache.put(101L, Map.of(10, 3400L));

        assertThat(cache.getIfPresent(101L)).containsEntry(10, 3400L);
    }
}
