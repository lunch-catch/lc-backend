package com.launchcatch.store.config;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "store.owner-terms")
public record OwnerTermsProperties(List<Term> policies) {
    public OwnerTermsProperties {
        policies = policies == null ? List.of() : List.copyOf(policies);
        if (policies.size() > 100) {
            throw new IllegalArgumentException("현재 약관 정책은 최대 100개까지 설정할 수 있습니다.");
        }
        Set<String> types = new HashSet<>();
        for (Term term : policies) {
            if (!types.add(term.termsType().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("약관 종류마다 현재 정책은 하나만 설정해야 합니다.");
            }
        }
    }

    public record Term(String termsType, String termsVersion, Boolean required) {
        public Term {
            if (termsType == null || termsType.isBlank() || termsType.length() > 50
                    || termsVersion == null || termsVersion.isBlank() || termsVersion.length() > 30
                    || !termsType.equals(termsType.strip()) || !termsVersion.equals(termsVersion.strip())
                    || required == null) {
                throw new IllegalArgumentException("약관 종류·버전·필수 여부 설정이 올바르지 않습니다.");
            }
        }
    }
}