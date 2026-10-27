package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.PageCursor;
import com.lunchcatch.global.response.PageTokens;
import com.lunchcatch.store.contract.StoreDiscountTargetType;
import com.lunchcatch.store.contract.StoreDiscountType;
import com.lunchcatch.store.dto.StoreCategoryFilter;
import com.lunchcatch.store.dto.StoreListItemResponse;
import com.lunchcatch.store.dto.StoreListRequest;
import com.lunchcatch.store.dto.StoreListRow;
import com.lunchcatch.store.repository.StoreRepository;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class StoreListServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T15:30:00Z"), ZoneId.of("Asia/Seoul"));
    @Mock StoreRepository repository;
    private StoreListService service;

    @BeforeEach
    void setUp() {
        service = new StoreListService(repository, "https://cdn.example.com", CLOCK);
    }

    @Test
    void 대표_이미지의_객체_키를_인코딩한_CDN_URL로_반환한다() {
        when(repository.findPage(isNull(), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)), eq(List.of()),
                isNull(), eq(LocalDate.of(2026, 10, 15)), eq(21)))
                .thenReturn(List.of(imageRow("stores/1/logo image.png")));
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), null, null);

        var result = service.find(request);

        assertThat(result.items().getFirst().storeImageUrl())
                .isEqualTo("https://cdn.example.com/stores/1/logo%20image.png");
    }

    @Test
    void 이미지가_없으면_CDN_설정_없이_null을_반환한다() {
        service = new StoreListService(repository, "", CLOCK);
        when(repository.findPage(isNull(), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)), eq(List.of()),
                isNull(), eq(LocalDate.of(2026, 10, 15)), eq(21))).thenReturn(List.of(imageRow(null)));
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), null, null);

        var result = service.find(request);

        assertThat(result.items().getFirst().storeId()).isEqualTo(1L);
        assertThat(result.items().getFirst().storeImageUrl()).isNull();
    }

    @Test
    void 이미지가_있는데_CDN_설정이_없으면_오류를_전파한다() {
        service = new StoreListService(repository, "", CLOCK);
        when(repository.findPage(isNull(), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)), eq(List.of()),
                isNull(), eq(LocalDate.of(2026, 10, 15)), eq(21)))
                .thenReturn(List.of(imageRow("stores/1/logo.png")));
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), null, null);

        assertThatThrownBy(() -> service.find(request)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cdn.base-url");
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
                distance, null, null, null, null, null, null, null, null);
    }

    private static StoreListRow imageRow(String objectKey) {
        return new StoreListRow(1L, "국수 가게", "KOREAN", BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                0, null, null, null, null, null, null, null, objectKey);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void 잘못된_이미지와_MENU_요약이_있어도_가게와_페이지_커서를_유지한다(String key) {
        service = new StoreListService(repository, "", CLOCK);
        when(repository.findPage(isNull(), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)), eq(List.of()),
                isNull(), eq(LocalDate.of(2026, 10, 15)), eq(3))).thenReturn(List.of(
                        new StoreListRow(1L, "오류 가게", "KOREAN", BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                                0.1, 30L, StoreDiscountTargetType.MENU,
                                StoreDiscountType.AMOUNT, 1000,
                                null, null, null, key), row(2, 0.2), row(3, 0.3)));
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), 2, null);

        var result = service.find(request);

        assertThat(result.items()).extracting(StoreListItemResponse::storeId).containsExactly(1L, 2L);
        assertThat(result.items().getFirst().storeImageUrl()).isNull();
        assertThat(result.items().getFirst().hasActiveCampaign()).isFalse();
        assertThat(result.items().getFirst().activeCampaign()).isNull();
        assertThat(PageTokens.decode(result.nextPageToken())).isEqualTo(new PageCursor(2L, "0.2"));
    }

    @Test
    void 목록_DB_장애는_빈_목록으로_바꾸지_않는다() {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.findPage(isNull(), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)), eq(List.of()),
                isNull(), eq(LocalDate.of(2026, 10, 15)), eq(21))).thenThrow(failure);
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), null, null);

        assertThatThrownBy(() -> service.find(request)).isSameAs(failure);
    }

    @Test
    void 이미지_키가_비어도_정상_MENU_할인은_유지한다() {
        when(repository.findPage(isNull(), eq(BigDecimal.valueOf(127)), eq(BigDecimal.valueOf(37)), eq(List.of()),
                isNull(), eq(LocalDate.of(2026, 10, 15)), eq(21))).thenReturn(List.of(
                        new StoreListRow(1L, "식당", "KOREAN", BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                                0, 30L, StoreDiscountTargetType.MENU, StoreDiscountType.AMOUNT, 1000,
                                20L, "국수", 9000L, " ")));
        var request = new StoreListRequest(null, BigDecimal.valueOf(127), BigDecimal.valueOf(37),
                List.of(StoreCategoryFilter.ALL), null, null);

        var item = service.find(request).items().getFirst();

        assertThat(item.storeImageUrl()).isNull();
        assertThat(item.hasActiveCampaign()).isTrue();
        assertThat(item.activeCampaign().targetMenu().menuId()).isEqualTo(20L);
        assertThat(item.activeCampaign().targetMenu().price()).isEqualTo(9000L);
    }

}
