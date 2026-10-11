package com.launchcatch.store.config;

import static org.assertj.core.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;

class OwnerTermsPropertiesTest {
    @Test
    void 실제_설정_키로_종류_버전_필수여부를_바인딩한다() {
        var source = new MapConfigurationPropertySource(Map.of(
                "store.owner-terms.policies[0].terms-type", "SERVICE",
                "store.owner-terms.policies[0].terms-version", "2026-10",
                "store.owner-terms.policies[0].required", "true"));
        var result = new Binder(source).bind("store.owner-terms", Bindable.of(OwnerTermsProperties.class)).get();
        assertThat(result.policies()).containsExactly(new OwnerTermsProperties.Term("SERVICE", "2026-10", true));
    }

    @Test
    void 정책_목록은_방어적으로_복사한다() {
        var original = new ArrayList<>(List.of(new OwnerTermsProperties.Term("SERVICE", "1.0", true)));
        var result = new OwnerTermsProperties(original);
        original.clear();
        assertThat(result.policies()).hasSize(1);
        assertThatThrownBy(() -> result.policies().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 빈_정책은_허용하지만_모호하거나_잘못된_정책은_기동시에_거부한다() {
        assertThat(new OwnerTermsProperties(null).policies()).isEmpty();
        assertThatThrownBy(() -> new OwnerTermsProperties(List.of(
                new OwnerTermsProperties.Term("SERVICE", "1.0", true),
                new OwnerTermsProperties.Term("service", "2.0", false)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnerTermsProperties.Term("SERVICE", "1.0", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnerTermsProperties.Term(" ", "1.0", true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OwnerTermsProperties.Term("SERVICE", "1.0 ", true)).isInstanceOf(IllegalArgumentException.class);
    }
}
