package com.launchcatch.coupon.service;

import static com.launchcatch.coupon.service.CouponBatchFixture.DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.launchcatch.coupon.CouponConsistencyFinding;
import com.launchcatch.coupon.repository.CouponConsistencyRepository;
import com.launchcatch.ops.contract.BatchAttemptGuard;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CouponConsistencyServiceTest {
    private final CouponConsistencyRepository repository = mock(CouponConsistencyRepository.class);
    private final LocalDateTime now = DATE.plusDays(1).atTime(2, 0);

    @Test
    void 불일치_대상과_기대값과_실제값을_로그에_남긴다() {
        when(repository.findings(DATE, "", 0, now))
                .thenReturn(List.of(new CouponConsistencyFinding("DAILY_COUNTER", 17L, 1, 0)));
        Logger logger = (Logger) LoggerFactory.getLogger(CouponConsistencyService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThat(service().verify(DATE, 1)).isEqualTo(1);
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("type=DAILY_COUNTER", "targetId=17", "expected=1", "actual=0"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void 정상인_날짜는_불일치_수를_영으로_반환한다() {
        when(repository.findings(DATE, "", 0, now)).thenReturn(List.of());
        assertThat(service().verify(DATE, 1)).isZero();
    }

    private CouponConsistencyService service() {
        ZoneId zone = ZoneId.of("Asia/Seoul");
        return new CouponConsistencyService(repository, mock(BatchAttemptGuard.class),
                Clock.fixed(now.atZone(zone).toInstant(), zone));
    }
}
