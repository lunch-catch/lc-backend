package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.PointPolicyResponse;
import com.launchcatch.billing.entity.PointPolicy;
import com.launchcatch.billing.repository.PointPolicyRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerPointPolicyServiceTest {

    // UTC 로는 9월 30일 15:30 이지만 KST 로는 10월 1일 00:30 이다
    private static final Clock JUST_AFTER_MIDNIGHT =
            Clock.fixed(Instant.parse("2026-09-30T15:30:00Z"), ClockConfig.ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Mock PointPolicyRepository repository;

    private OwnerPointPolicyService service() {
        return new OwnerPointPolicyService(repository, JUST_AFTER_MIDNIGHT);
    }

    @Test
    void 오늘_적용_중인_정책을_돌려준다() {
        PointPolicy policy = PointPolicy.effectiveFrom(TODAY, 10000, List.of(10000L, 30000L), null, 1L);
        when(repository.findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(TODAY))
                .thenReturn(Optional.of(policy));

        PointPolicyResponse response = service().getCurrent();

        assertThat(response.minChargeAmount()).isEqualTo(10000);
        assertThat(response.chargeProducts()).containsExactly(10000L, 30000L);
        assertThat(response.effectiveDate()).isEqualTo(TODAY);
    }

    @Test
    void 오늘은_서버_시간대가_아니라_KST_날짜로_정한다() {
        PointPolicy policy = PointPolicy.effectiveFrom(TODAY, 10000, List.of(10000L), null, 1L);
        when(repository.findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(TODAY))
                .thenReturn(Optional.of(policy));

        assertThat(service().currentPolicy()).isSameAs(policy);
    }

    @Test
    void 적용_중인_정책이_없으면_숨기지_않고_서버_오류로_드러낸다() {
        when(repository.findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(TODAY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getCurrent())
                .isInstanceOf(IllegalStateException.class);
    }
}
