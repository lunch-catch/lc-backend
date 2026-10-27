package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.OwnerLedgerItem;
import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.LedgerRow;
import com.lunchcatch.billing.repository.OwnerLedgerCondition;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.global.exception.CommonErrorCode;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.PageCursor;
import com.lunchcatch.global.response.PageTokens;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OwnerPointLedgerServiceTest {

    private static final Long OWNER = 7L;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 0, 0, 5);

    @Mock PointLedgerRepository repository;
    @InjectMocks OwnerPointLedgerService service;

    private static LedgerRow row(long id, EntryType type, long amount, LocalDateTime at) {
        return new LedgerRow(id, type, amount, null, null, null, at);
    }

    private OwnerLedgerCondition captureCondition() {
        ArgumentCaptor<OwnerLedgerCondition> captor = ArgumentCaptor.forClass(OwnerLedgerCondition.class);
        verify(repository).findOwnerLedger(captor.capture());
        return captor.getValue();
    }

    @Test
    void 종류를_지정하지_않으면_DEDUCT를_뺀_다섯_종류를_조회한다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of());

        service.list(OWNER, null, null, null, null, null, 20);

        OwnerLedgerCondition condition = captureCondition();
        assertThat(condition.types()).containsExactlyInAnyOrder(
                EntryType.CHARGE, EntryType.RESERVE, EntryType.RELEASE, EntryType.REFUND, EntryType.ADJUST);
        assertThat(condition.ownerId()).isEqualTo(OWNER);
        assertThat(condition.limit()).isEqualTo(21);
    }

    @Test
    void 빈_종류_목록도_지정하지_않은_것으로_본다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of());

        service.list(OWNER, List.of(), null, null, null, null, 20);

        assertThat(captureCondition().types()).hasSize(5).doesNotContain(EntryType.DEDUCT);
    }

    @Test
    void 지정한_종류만_조회한다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of());

        service.list(OWNER, List.of(EntryType.CHARGE, EntryType.REFUND), null, null, null, null, 20);

        assertThat(captureCondition().types()).isEqualTo(EnumSet.of(EntryType.CHARGE, EntryType.REFUND));
    }

    @Test
    void DEDUCT를_종류로_지정하면_거절한다() {
        assertThatThrownBy(() -> service.list(OWNER, List.of(EntryType.DEDUCT), null, null, null, null, 20))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
        verify(repository, never()).findOwnerLedger(any());
    }

    @Test
    void 기간이_뒤집히면_거절한다() {
        assertThatThrownBy(() -> service.list(OWNER, null,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), null, null, 20))
                .isInstanceOf(BillingException.class);
    }

    @Test
    void 날짜는_시작일_자정부터_종료일_다음날_자정_전까지의_범위로_바꾼다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of());

        service.list(OWNER, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 12L, null, 20);

        OwnerLedgerCondition condition = captureCondition();
        assertThat(condition.fromInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(condition.toExclusive()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(condition.campaignId()).isEqualTo(12L);
    }

    @Test
    void 한쪽만_준_기간은_다른_쪽을_걸지_않는다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of());

        service.list(OWNER, null, null, LocalDate.of(2026, 9, 30), null, null, 20);

        OwnerLedgerCondition condition = captureCondition();
        assertThat(condition.fromInclusive()).isNull();
        assertThat(condition.toExclusive()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
    }

    @Test
    void 한_건_더_읽어_다음_페이지가_있으면_토큰을_만들고_초과분은_자른다() {
        List<LedgerRow> rows = new ArrayList<>();
        rows.add(row(9, EntryType.RESERVE, -10000, AT));
        rows.add(row(8, EntryType.CHARGE, 30000, AT.minusHours(6)));
        rows.add(row(7, EntryType.CHARGE, 10000, AT.minusHours(7)));
        when(repository.findOwnerLedger(any())).thenReturn(rows);

        CursorPageResponse<OwnerLedgerItem> response = service.list(OWNER, null, null, null, null, null, 2);

        assertThat(response.items()).extracting(OwnerLedgerItem::ledgerId).containsExactly(9L, 8L);
        PageCursor next = PageTokens.decode(response.nextPageToken());
        assertThat(next.id()).isEqualTo(8L);
        assertThat(next.sortValue()).isEqualTo(AT.minusHours(6).toString());
    }

    @Test
    void 마지막_페이지는_토큰이_없다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of(row(9, EntryType.RESERVE, -10000, AT)));

        CursorPageResponse<OwnerLedgerItem> response = service.list(OWNER, null, null, null, null, null, 2);

        assertThat(response.nextPageToken()).isNull();
        OwnerLedgerItem item = response.items().get(0);
        assertThat(item.amount()).isEqualTo(-10000);
        assertThat(item.createdAt().toString()).isEqualTo("2026-10-01T00:00:05+09:00");
    }

    @Test
    void 토큰의_위치를_커서_조건으로_넘긴다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of());
        String token = PageTokens.encode(new PageCursor(8L, AT.toString()));

        service.list(OWNER, null, null, null, null, token, 20);

        OwnerLedgerCondition condition = captureCondition();
        assertThat(condition.cursorCreatedAt()).isEqualTo(AT);
        assertThat(condition.cursorId()).isEqualTo(8L);
    }

    @Test
    void 토큰의_시각을_읽을_수_없으면_첫_페이지로_시작한다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of());

        service.list(OWNER, null, null, null, null, PageTokens.encode(new PageCursor(8L, "garbage")), 20);
        service.list(OWNER, null, null, null, null, PageTokens.encode(new PageCursor(8L, null)), 20);

        ArgumentCaptor<OwnerLedgerCondition> captor = ArgumentCaptor.forClass(OwnerLedgerCondition.class);
        verify(repository, org.mockito.Mockito.times(2)).findOwnerLedger(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(c -> {
            assertThat(c.cursorCreatedAt()).isNull();
            assertThat(c.cursorId()).isNull();
        });
    }

    @Test
    void 페이지_크기는_1에서_100_사이로_맞춘다() {
        when(repository.findOwnerLedger(any())).thenReturn(List.of());

        service.list(OWNER, null, null, null, null, null, 0);
        service.list(OWNER, null, null, null, null, null, 500);

        ArgumentCaptor<OwnerLedgerCondition> captor = ArgumentCaptor.forClass(OwnerLedgerCondition.class);
        verify(repository, org.mockito.Mockito.times(2)).findOwnerLedger(captor.capture());
        assertThat(captor.getAllValues()).extracting(OwnerLedgerCondition::limit).containsExactly(21, 101);
    }
}
