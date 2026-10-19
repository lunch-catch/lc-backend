package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.launchcatch.billing.dto.CampaignCounterMismatch;
import com.launchcatch.billing.dto.CampaignOverspend;
import com.launchcatch.billing.repository.SettlementMismatchRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class SpendMismatchRecorderTest {

    private static final LocalDate PREV = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 0, 0);

    private final SettlementMismatchRepository repository = mock(SettlementMismatchRepository.class);
    private final SpendMismatchRecorder recorder = new SpendMismatchRecorder(repository);

    @Test
    void 초과_소진과_카운터_불일치를_종류별_무변경_upsert로_기록한다() {
        recorder.record(PREV, 42L, NOW,
                List.of(new CampaignOverspend(7L, 1000L, 1200L)),
                List.of(new CampaignCounterMismatch(8L, 400L, 500L)));

        // 초과 소진은 기대값 예약액·실제값 원장, 카운터 불일치는 기대값 원장·실제값 카운터다
        verify(repository).insertCampaignOverspendIfAbsent(PREV, 7L, 1000L, 1200L, 42L, NOW);
        verify(repository).insertCampaignSpendMismatchIfAbsent(PREV, 8L, 400L, 500L, 42L, NOW);
    }

    @Test
    void 기록할_것이_없으면_쓰지_않는다() {
        recorder.record(PREV, 42L, NOW, List.of(), List.of());

        verifyNoInteractions(repository);
    }

    @Test
    void 기록은_트랜잭션_하나다() throws Exception {
        assertThat(SpendMismatchRecorder.class.getMethod("record", LocalDate.class, Long.class, LocalDateTime.class,
                List.class, List.class).isAnnotationPresent(Transactional.class)).isTrue();
    }
}
