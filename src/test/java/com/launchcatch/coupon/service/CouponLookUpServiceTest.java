package com.launchcatch.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.launchcatch.coupon.dto.CouponIssuanceLimitResponse;
import com.launchcatch.coupon.repository.CouponDailyLimitRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("일일 발급 한도 조회")
class CouponLookUpServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Mock CouponDailyLimitRepository repository;

    private CouponLookUpService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC);
        service = configuredService(clock, 3);
    }

    @Test
    void 오늘_발급_기록이_없으면_0건이다() {
        when(repository.findIssuedCountByMemberIdAndBusinessDate(1L, TODAY)).thenReturn(Optional.empty());

        CouponIssuanceLimitResponse response = service.getIssuanceLimit(1L);

        assertThat(response).isEqualTo(new CouponIssuanceLimitResponse(TODAY, 0, 3, false));
    }

    @ParameterizedTest
    @CsvSource({"0, false", "1, false", "2, false", "3, true"})
    void 저장된_누적_발급_횟수로_한도_도달_여부를_계산한다(int issuedCount, boolean reached) {
        when(repository.findIssuedCountByMemberIdAndBusinessDate(1L, TODAY))
                .thenReturn(Optional.of(issuedCount));

        CouponIssuanceLimitResponse response = service.getIssuanceLimit(1L);

        assertThat(response).isEqualTo(new CouponIssuanceLimitResponse(TODAY, issuedCount, 3, reached));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-09T14:59:59Z, 2026-10-09",
            "2026-10-09T15:00:00Z, 2026-10-10",
            "2026-10-09T15:00:01Z, 2026-10-10"
    })
    void 영업일은_시계의_시간대와_관계없이_한국_자정을_기준으로_한다(Instant instant, LocalDate expectedDate) {
        Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
        CouponLookUpService boundaryService = configuredService(clock, 3);
        when(repository.findIssuedCountByMemberIdAndBusinessDate(1L, expectedDate)).thenReturn(Optional.of(2));

        CouponIssuanceLimitResponse response = boundaryService.getIssuanceLimit(1L);

        assertThat(response).isEqualTo(new CouponIssuanceLimitResponse(expectedDate, 2, 3, false));
    }

    @ParameterizedTest
    @CsvSource({"1, false", "2, true", "3, true"})
    void 주입받은_한도로_응답과_도달_여부를_계산한다(int issuedCount, boolean reached) {
        Clock clock = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC);
        CouponLookUpService configuredService = configuredService(clock, 2);
        when(repository.findIssuedCountByMemberIdAndBusinessDate(1L, TODAY))
                .thenReturn(Optional.of(issuedCount));

        CouponIssuanceLimitResponse response = configuredService.getIssuanceLimit(1L);

        assertThat(response).isEqualTo(new CouponIssuanceLimitResponse(TODAY, issuedCount, 2, reached));
    }

    // 지정한 시계와 일일 발급 한도를 사용하는 조회 서비스를 구성한다.
    private CouponLookUpService configuredService(Clock clock, int dailyLimit) {
        CouponLookUpService configured = new CouponLookUpService(repository, clock);
        ReflectionTestUtils.setField(configured, "dailyLimit", dailyLimit);
        return configured;
    }
}
