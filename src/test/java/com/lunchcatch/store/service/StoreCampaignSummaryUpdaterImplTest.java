package com.lunchcatch.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.store.contract.StoreCampaignStatus;
import com.lunchcatch.store.contract.StoreCampaignSummaryCommand;
import com.lunchcatch.store.contract.StoreCampaignSummaryUpdate;
import com.lunchcatch.store.contract.StoreDiscountTargetType;
import com.lunchcatch.store.contract.StoreDiscountType;
import com.lunchcatch.store.repository.StoreCampaignSummaryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreCampaignSummaryUpdaterImplTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    @Mock StoreCampaignSummaryRepository repository;
    private StoreCampaignSummaryUpdaterImpl service;

    @BeforeEach
    void setUp() {
        service = new StoreCampaignSummaryUpdaterImpl(repository,
                Clock.fixed(Instant.parse("2026-10-14T15:30:00Z"), ZoneId.of("Asia/Seoul")));
    }

    @Test
    void 전체_할인_요약과_서울_기준_저장_시각을_전달한다() {
        var command = command(StoreDiscountTargetType.ALL, null, StoreDiscountType.AMOUNT, 3000);

        service.update(1, 2, command);

        verify(repository).upsert(1, 2, command, LocalDateTime.of(2026, 10, 15, 0, 30));
    }

    @Test
    void 해당_가게의_메뉴만_할인_대상으로_저장한다() {
        var command = command(StoreDiscountTargetType.MENU, 3L, StoreDiscountType.PERCENT, 20);
        when(repository.menuBelongsToStore(1, 3)).thenReturn(true);

        service.update(1, 2, command);

        verify(repository).upsert(1, 2, command, LocalDateTime.of(2026, 10, 15, 0, 30));
    }

    @Test
    void 다른_가게의_메뉴를_요약에_저장하지_않는다() {
        var command = command(StoreDiscountTargetType.MENU, 3L, StoreDiscountType.PERCENT, 20);

        assertThatThrownBy(() -> service.update(1, 2, command)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).upsert(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 필수값과_양수_ID를_검증한다() {
        var command = command(StoreDiscountTargetType.ALL, null, StoreDiscountType.AMOUNT, 3000);

        assertThatThrownBy(() -> service.update(0, 2, command)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, -1, command)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, 2, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 할인_유형에_따라_메뉴_ID와_할인_값을_검증한다() {
        assertThatThrownBy(() -> service.update(1, 2,
                command(StoreDiscountTargetType.MENU, null, StoreDiscountType.PERCENT, 20)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, 2,
                command(StoreDiscountTargetType.ALL, 3L, StoreDiscountType.AMOUNT, 3000)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, 2,
                command(StoreDiscountTargetType.ALL, null, StoreDiscountType.PERCENT, 101)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(1, 2,
                command(StoreDiscountTargetType.ALL, null, StoreDiscountType.AMOUNT, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 종료_시각은_시작_시각보다_늦어야_한다() {
        var command = new StoreCampaignSummaryCommand(TODAY, StoreCampaignStatus.ACTIVE,
                StoreDiscountTargetType.ALL, StoreDiscountType.AMOUNT, 3000, null,
                LocalTime.of(13, 0), LocalTime.of(12, 0));

        assertThatThrownBy(() -> service.update(1, 2, command)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 빈_일괄_입력은_DB에_접근하지_않는다() {
        service.updateAll(List.of());

        verifyNoInteractions(repository);
    }

    @Test
    void 일괄_입력의_null과_잘못된_항목을_저장_전에_거부한다() {
        var valid = new StoreCampaignSummaryUpdate(1, 2,
                command(StoreDiscountTargetType.ALL, null, StoreDiscountType.AMOUNT, 3000));
        var invalid = new StoreCampaignSummaryUpdate(0, 3, valid.command());

        assertThatThrownBy(() -> service.updateAll(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateAll(Arrays.asList(valid, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateAll(List.of(valid, invalid)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void 전체_할인_요약은_메뉴_조회_없이_한번에_저장한다() {
        var updates = List.of(new StoreCampaignSummaryUpdate(1, 2,
                command(StoreDiscountTargetType.ALL, null, StoreDiscountType.AMOUNT, 3000)));

        service.updateAll(updates);

        verify(repository).upsertAll(updates, LocalDateTime.of(2026, 10, 15, 0, 30));
        verify(repository, never()).findMenuStoreIds(any());
    }

    @Test
    void 일괄_메뉴_요약은_소속을_한번에_조회하여_저장한다() {
        var updates = List.of(
                new StoreCampaignSummaryUpdate(1, 2,
                        command(StoreDiscountTargetType.MENU, 3L, StoreDiscountType.PERCENT, 20)),
                new StoreCampaignSummaryUpdate(4, 5,
                        command(StoreDiscountTargetType.MENU, 6L, StoreDiscountType.AMOUNT, 1000)));
        when(repository.findMenuStoreIds(List.of(3L, 6L))).thenReturn(Map.of(3L, 1L, 6L, 4L));

        service.updateAll(updates);

        verify(repository).upsertAll(updates, LocalDateTime.of(2026, 10, 15, 0, 30));
    }

    @Test
    void 일괄_입력에_다른_가게의_메뉴가_있으면_저장하지_않는다() {
        var updates = List.of(new StoreCampaignSummaryUpdate(1, 2,
                command(StoreDiscountTargetType.MENU, 3L, StoreDiscountType.PERCENT, 20)));
        when(repository.findMenuStoreIds(List.of(3L))).thenReturn(Map.of(3L, 99L));

        assertThatThrownBy(() -> service.updateAll(updates)).isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).upsertAll(any(), any());
    }

    @Test
    void 오백개를_초과하면_나누어_저장하고_동일한_시각을_사용한다() {
        var command = command(StoreDiscountTargetType.ALL, null, StoreDiscountType.AMOUNT, 3000);
        List<StoreCampaignSummaryUpdate> updates = IntStream.rangeClosed(1, 501)
                .mapToObj(id -> new StoreCampaignSummaryUpdate(id, id, command)).toList();

        service.updateAll(updates);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StoreCampaignSummaryUpdate>> batches = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).upsertAll(batches.capture(),
                org.mockito.ArgumentMatchers.eq(LocalDateTime.of(2026, 10, 15, 0, 30)));
        assertThat(batches.getAllValues()).extracting(List::size).containsExactly(500, 1);
        assertThat(batches.getAllValues().get(1)).containsExactly(updates.getLast());
    }

    private static StoreCampaignSummaryCommand command(StoreDiscountTargetType type, Long menuId,
            StoreDiscountType discountType, int value) {
        return new StoreCampaignSummaryCommand(TODAY, StoreCampaignStatus.ACTIVE, type, discountType, value,
                menuId, LocalTime.of(11, 30), LocalTime.of(15, 0));
    }
}
