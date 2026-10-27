package com.lunchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lunchcatch.campaign.contract.AgeGroup;
import com.lunchcatch.campaign.contract.StoreAudienceCell;
import com.lunchcatch.campaign.contract.TargetGender;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 유일 키/성별 거부 같은 판정 로직은 여기서 jdbcTemplate을 모킹해 검증한다. 다중 행
 * upsert SQL이 실제로 덮어쓰는지는 StoreAudienceUpdaterImplIntegrationTest가 실제
 * MySQL로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class StoreAudienceUpdaterImplTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Captor
    private ArgumentCaptor<Object[]> paramsCaptor;

    private StoreAudienceUpdaterImpl updater;

    @BeforeEach
    void setUp() {
        updater = new StoreAudienceUpdaterImpl(jdbcTemplate, FIXED_CLOCK);
    }

    @Test
    @DisplayName("셀 하나면 upsert SQL을 한 번만 부른다")
    void 셀_하나면_한_번만_부른다() {
        StoreAudienceCell cell = new StoreAudienceCell(1L, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 42, BUSINESS_DATE);

        updater.overwrite(List.of(cell));

        verify(jdbcTemplate, times(1)).update(anyString(), paramsCaptor.capture());
        LocalDateTime now = LocalDateTime.now(FIXED_CLOCK);
        assertThat(paramsCaptor.getValue())
                .containsExactly(1L, 1000, "MALE", "AGE_20S", 42, BUSINESS_DATE, now, now);
    }

    @Test
    @DisplayName("FEMALE도 그대로 넘긴다")
    void FEMALE도_그대로_넘긴다() {
        StoreAudienceCell cell = new StoreAudienceCell(1L, 1000, TargetGender.FEMALE, AgeGroup.AGE_30S, 10, BUSINESS_DATE);

        updater.overwrite(List.of(cell));

        verify(jdbcTemplate).update(anyString(), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).contains("FEMALE", "AGE_30S");
    }

    @Test
    @DisplayName("ALL은 거부하지 않고 그대로 저장한다(#218)")
    void ALL도_저장한다() {
        StoreAudienceCell cell = new StoreAudienceCell(1L, 1000, TargetGender.ALL, AgeGroup.AGE_20S, 1, BUSINESS_DATE);

        updater.overwrite(List.of(cell));

        verify(jdbcTemplate).update(anyString(), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).contains("ALL");
    }

    @Test
    @DisplayName("cells가 null이면 예외로 거부한다")
    void cells가_null이면_거부한다() {
        assertThatThrownBy(() -> updater.overwrite(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("1000행을 넘기면 예외로 거부하고 SQL을 한 번도 안 보낸다")
    void 천행을_넘기면_거부한다() {
        List<StoreAudienceCell> cells = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            cells.add(new StoreAudienceCell(i, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 1, BUSINESS_DATE));
        }

        assertThatThrownBy(() -> updater.overwrite(cells)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("정확히 1000행이면 거부하지 않고 250행씩 묶어 4번만 SQL을 보낸다")
    void 정확히_천행이면_이백오십행씩_네_번_보낸다() {
        List<StoreAudienceCell> cells = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            cells.add(new StoreAudienceCell(i, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 1, BUSINESS_DATE));
        }

        updater.overwrite(cells);

        verify(jdbcTemplate, times(4)).update(anyString(), paramsCaptor.capture());
        assertThat(paramsCaptor.getAllValues()).allSatisfy(params -> assertThat(params).hasSize(250 * 8));
    }

    @Test
    @DisplayName("251행이면 250행 묶음 하나와 1행 묶음 하나, 두 번만 SQL을 보낸다")
    void 이백오십일행이면_두_번_보낸다() {
        List<StoreAudienceCell> cells = new ArrayList<>();
        for (int i = 0; i < 251; i++) {
            cells.add(new StoreAudienceCell(i, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 1, BUSINESS_DATE));
        }

        updater.overwrite(cells);

        verify(jdbcTemplate, times(2)).update(anyString(), any(Object[].class));
    }
}
