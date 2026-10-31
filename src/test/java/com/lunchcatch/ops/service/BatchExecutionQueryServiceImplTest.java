package com.lunchcatch.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lunchcatch.ops.contract.BatchExecutionRecord;
import com.lunchcatch.ops.entity.BatchExecutionLog;
import com.lunchcatch.ops.entity.BatchStatus;
import com.lunchcatch.ops.repository.BatchExecutionLogRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BatchExecutionQueryServiceImplTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);

    @Mock
    private BatchExecutionLogRepository batchExecutionLogRepository;

    private BatchExecutionQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BatchExecutionQueryServiceImpl(batchExecutionLogRepository);
    }

    @Test
    @DisplayName("그 영업일의 기록을 BatchExecutionRecord로 옮긴다")
    void 그_영업일의_기록을_옮긴다() {
        BatchExecutionLog log = BatchExecutionLog.running("DAILY_0000", BUSINESS_DATE, "server-1");
        when(batchExecutionLogRepository.findByBusinessDate(BUSINESS_DATE)).thenReturn(List.of(log));

        List<BatchExecutionRecord> result = service.findByBusinessDate(BUSINESS_DATE);

        assertThat(result).hasSize(1);
        BatchExecutionRecord record = result.get(0);
        assertThat(record.jobName()).isEqualTo("DAILY_0000");
        assertThat(record.businessDate()).isEqualTo(BUSINESS_DATE);
        assertThat(record.status()).isEqualTo(BatchStatus.RUNNING.name());
        assertThat(record.executedBy()).isEqualTo("server-1");
        assertThat(record.retryCount()).isZero();
        assertThat(record.failureReason()).isNull();
        assertThat(record.attemptNo()).isEqualTo(1);
    }

    /*
     * 성공한 영업일 조회는 저장소에 그대로 넘긴다.
     * 분석이 전날 실패한 영업일을 가려내려고 부른다. 기간은 시작 포함, 끝 제외다.
     */
    @Test
    @DisplayName("성공한 영업일 조회를 저장소에 그대로 넘긴다")
    void 성공한_영업일_조회를_넘긴다() {
        LocalDate from = LocalDate.of(2026, 10, 10);
        LocalDate to = LocalDate.of(2026, 10, 14);
        when(batchExecutionLogRepository.findSucceededBusinessDates("DAILY_AGGREGATION", from, to))
                .thenReturn(List.of(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 13)));

        List<LocalDate> result = service.findSucceededBusinessDates("DAILY_AGGREGATION", from, to);

        assertThat(result).containsExactly(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 13));
    }
}
