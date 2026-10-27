package com.lunchcatch.billing.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class TossPropertiesTest {

    @Test
    void 비밀키만_주면_주소와_타임아웃은_시작값으로_채워진다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("billing.toss.secret-key", "sk")));

        TossProperties properties = binder.bind("billing.toss", TossProperties.class).get();

        assertThat(properties.baseUrl()).isEqualTo("https://api.tosspayments.com");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void 타임아웃은_설정으로_바꾼다() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "billing.toss.secret-key", "sk",
                "billing.toss.connect-timeout", "1s",
                "billing.toss.read-timeout", "20s")));

        TossProperties properties = binder.bind("billing.toss", TossProperties.class).get();

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void 환경변수가_없으면_비밀키가_비어_기동_검사에_걸린다() throws Exception {
        // application.yml 의 첫 문서(공통 설정)만 읽는다. local 문서는 기본 키를 따로 둔다
        PropertySource<?> common = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0);
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(common);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));

        String secretKey = binder.bind("billing.toss.secret-key", String.class).get();

        assertThat(secretKey).isEmpty();
    }

    @Test
    void 문자열로_찍어도_비밀키가_드러나지_않는다() {
        TossProperties properties =
                new TossProperties("https://api.tosspayments.com", "test_sk_SECRET", Duration.ofSeconds(3), Duration.ofSeconds(10));

        assertThat(properties.toString()).doesNotContain("test_sk_SECRET").contains("****");
    }
}
