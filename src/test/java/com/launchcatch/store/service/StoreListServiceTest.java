package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import com.launchcatch.store.dto.StoreCategoryFilter;
import com.launchcatch.store.dto.StoreListItemResponse;
import com.launchcatch.store.dto.StoreListRequest;
import com.launchcatch.store.dto.StoreListRow;
import com.launchcatch.store.repository.StoreRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreListServiceTest {
    @Mock StoreRepository repository;
    private StoreListService service;

    @BeforeEach
    void setUp() {
        service = new StoreListService(repository,
                Clock.fixed(Instant.parse("2026-10-14T15:30:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void 커서를_생략하면_기본_크기와_서울_기준_오늘로_첫_페이지를_조회한다(String token) {
        var page = CursorPageResponse.<StoreListItemResponse>of(List.of(), null);
        when(repository.findPage(isNull(), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)), eq(List.of()),
                isNull(), eq(LocalDate.of(2026, 10, 15)), eq(21))).thenReturn(List.of());
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), null, token);

        assertThat(service.find(request)).isEqualTo(page);
    }

    @Test
    void 커서를_복원하고_중복_카테고리를_제거하여_다음_페이지를_조회한다() {
        var cursor = new PageCursor(2L, "0.234567891");
        StoreListRow last = row(3, 310.5);
        when(repository.findPage(eq("국수"), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)),
                eq(List.of("KOREAN", "JAPANESE")), eq(cursor), eq(LocalDate.of(2026, 10, 15)), eq(3)))
                .thenReturn(List.of(last));
        var request = new StoreListRequest("국수", BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.KOREAN, StoreCategoryFilter.JAPANESE, StoreCategoryFilter.KOREAN),
                2, PageTokens.encode(cursor));

        var result = service.find(request);

        assertThat(result.items()).extracting(StoreListItemResponse::storeId).containsExactly(3L);
        assertThat(result.items().getFirst().distanceMeters()).isEqualTo(311);
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 다음_페이지가_있으면_마지막_반환_가게의_반올림_전_거리를_커서에_담는다() {
        when(repository.findPage(isNull(), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)), eq(List.of()),
                isNull(), eq(LocalDate.of(2026, 10, 15)), eq(3)))
                .thenReturn(List.of(row(1, 0.111111111), row(2, 0.234567891), row(3, 0.3)));
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), 2, null);

        var result = service.find(request);

        assertThat(result.items()).extracting(StoreListItemResponse::storeId).containsExactly(1L, 2L);
        assertThat(PageTokens.decode(result.nextPageToken())).isEqualTo(new PageCursor(2L, "0.234567891"));
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void 잘못된_형식이나_거리의_커서를_거부한다(String token) {
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), 20, token);

        assertThatThrownBy(() -> service.find(request)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> invalidTokens() {
        return Stream.of("invalid", PageTokens.encode(new PageCursor(0L, "0")),
                PageTokens.encode(new PageCursor(1L, null)), PageTokens.encode(new PageCursor(1L, "NaN")),
                PageTokens.encode(new PageCursor(1L, "Infinity")), PageTokens.encode(new PageCursor(1L, "-1")),
                PageTokens.encode(new PageCursor(1L, "not-a-distance")));
    }

    private static StoreListRow row(long id, double distance) {
        return new StoreListRow(id, "국수 가게", "KOREAN", BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                distance, null, null, null, null, null, null, null);
    }
}
