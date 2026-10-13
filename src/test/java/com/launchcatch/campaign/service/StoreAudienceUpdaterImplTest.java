package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.repository.StoreAudienceRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreAudienceUpdaterImplTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private StoreAudienceRepository storeAudienceRepository;

    private StoreAudienceUpdaterImpl updater;

    @BeforeEach
    void setUp() {
        updater = new StoreAudienceUpdaterImpl(storeAudienceRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("셀마다 유일 키로 upsert를 부른다")
    void 셀마다_upsert를_부른다() {
        StoreAudienceCell cell = new StoreAudienceCell(1L, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 42, BUSINESS_DATE);

        updater.overwrite(List.of(cell));

        verify(storeAudienceRepository).upsert(
                1L, 1000, "MALE", "AGE_20S", 42, BUSINESS_DATE, LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("FEMALE도 그대로 넘긴다")
    void FEMALE도_그대로_넘긴다() {
        StoreAudienceCell cell = new StoreAudienceCell(1L, 1000, TargetGender.FEMALE, AgeGroup.AGE_30S, 10, BUSINESS_DATE);

        updater.overwrite(List.of(cell));

        verify(storeAudienceRepository).upsert(
                1L, 1000, "FEMALE", "AGE_30S", 10, BUSINESS_DATE, LocalDateTime.now(FIXED_CLOCK));
    }

    @Test
    @DisplayName("ALL은 store_audience.gender가 받지 않는 값이라 거부한다(#162 선행 필요)")
    void ALL은_거부한다() {
        StoreAudienceCell cell = new StoreAudienceCell(1L, 1000, TargetGender.ALL, AgeGroup.AGE_20S, 1, BUSINESS_DATE);

        assertThatThrownBy(() -> updater.overwrite(List.of(cell))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cells가 null이면 예외로 거부한다")
    void cells가_null이면_거부한다() {
        assertThatThrownBy(() -> updater.overwrite(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("1000행을 넘기면 예외로 거부하고 upsert를 한 번도 안 부른다")
    void 천행을_넘기면_거부한다() {
        List<StoreAudienceCell> cells = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            cells.add(new StoreAudienceCell(i, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 1, BUSINESS_DATE));
        }

        assertThatThrownBy(() -> updater.overwrite(cells)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(storeAudienceRepository);
    }

    @Test
    @DisplayName("정확히 1000행이면 거부하지 않고 1000번 upsert를 부른다")
    void 정확히_천행이면_통과한다() {
        List<StoreAudienceCell> cells = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            cells.add(new StoreAudienceCell(i, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 1, BUSINESS_DATE));
        }

        updater.overwrite(cells);

        verify(storeAudienceRepository, times(1000)).upsert(
                anyLong(), eq(1000), eq("MALE"), eq("AGE_20S"), eq(1), eq(BUSINESS_DATE),
                eq(LocalDateTime.now(FIXED_CLOCK)));
    }
}
