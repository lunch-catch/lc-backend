package com.lunchcatch.adserving.config;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

// 테스트가 쓰는 조정 값의 기본 설정이다. 값을 다시 적지 않고 레코드의 @DefaultValue 로 만든다
public final class AdservingTuningDefaults {

    private AdservingTuningDefaults() {
    }

    public static ImpressionWriteBufferProperties writeBuffer() {
        return bind("adserving.impression.write-buffer", ImpressionWriteBufferProperties.class, Map.of());
    }

    public static ServeLogBufferProperties serveLogBuffer() {
        return bind("adserving.serve-log.buffer", ServeLogBufferProperties.class, Map.of());
    }

    public static ImpressionDispatchProperties dispatch() {
        return bind("adserving.impression.dispatch", ImpressionDispatchProperties.class, Map.of());
    }

    public static AdservingCacheProperties cache() {
        return bind("adserving.cache", AdservingCacheProperties.class, Map.of());
    }

    static <T> T bind(String prefix, Class<T> type, Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate(prefix, type);
    }
}
