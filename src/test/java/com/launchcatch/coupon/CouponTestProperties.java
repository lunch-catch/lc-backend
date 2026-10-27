package com.launchcatch.coupon;

import com.launchcatch.coupon.config.CouponProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

public final class CouponTestProperties {

    private static final List<PropertySource<?>> CONFIGURATION = loadConfiguration();

    private CouponTestProperties() { }

    public static CouponProperties defaults() {
        return with(Map.of());
    }

    public static CouponProperties with(Map<String, Object> overrides) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("coupon-test", overrides));
        CONFIGURATION.forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment).bind("coupon", CouponProperties.class).orElseThrow(IllegalStateException::new);
    }

    private static List<PropertySource<?>> loadConfiguration() {
        try {
            return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
