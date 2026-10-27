package com.lunchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lunchcatch.billing.dto.AdminLedgerItem;
import com.lunchcatch.billing.dto.BalanceStep;
import com.lunchcatch.billing.entity.EntryType;
import com.lunchcatch.billing.entity.LedgerSource;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.AdminLedgerCondition;
import com.lunchcatch.billing.repository.AdminLedgerRow;
import com.lunchcatch.billing.repository.PointLedgerRepository;
import com.lunchcatch.global.exception.CommonErrorCode;
import com.lunchcatch.global.response.CursorPageResponse;
import com.lunchcatch.global.response.PageCursor;
import com.lunchcatch.global.response.PageTokens;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminPointLedgerServiceTest {

    private static final Long OWNER = 7L;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 2, 0, 0);
    private static final LocalDateTime RESERVED_AT = LocalDateTime.of(2026, 10, 1, 0, 0, 5);
    private static final LocalDateTime DEDUCTED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LocalDateTime RELEASED_AT = LocalDateTime.of(2026, 10, 1, 13, 30);

    @Mock PointLedgerRepository repository;
    @InjectMocks AdminPointLedgerService service;

    private AdminLedgerCondition captureCondition() {
        ArgumentCaptor<AdminLedgerCondition> captor = ArgumentCaptor.forClass(AdminLedgerCondition.class);
        verify(repository).findAdminLedger(captor.capture());
        return captor.getValue();
    }

    @Test
    void 변동_전후_잔액은_기간_시작_잔액에서_시간순으로_누적한다() {
        UUID serveId = UUID.randomUUID();
        when(repository.sumBalanceBefore(OWNER, START)).thenReturn(22000L);
        when(repository.findBalanceSteps(OWNER, START, END)).thenReturn(List.of(
                new BalanceStep(10L, RESERVED_AT, -10000L),
                new BalanceStep(11L, RELEASED_AT, 200L)));
        when(repository.findAdminLedger(any())).thenReturn(List.of(
                new AdminLedgerRow(11L, OWNER, EntryType.RELEASE, 200L, 12L, null, null,
                        LedgerSource.RECONCILIATION, null, RELEASED_AT),
                new AdminLedgerRow(12L, OWNER, EntryType.DEDUCT, -500L, 12L, serveId, null,
                        LedgerSource.REALTIME, null, DEDUCTED_AT),
                new AdminLedgerRow(10L, OWNER, EntryType.RESERVE, -10000L, 12L, null, "tgen_1",
                        LedgerSource.RECONCILIATION, null, RESERVED_AT)));

        CursorPageResponse<AdminLedgerItem> response = service.search(OWNER, null, DAY, DAY, null, null, 20, null);

        List<AdminLedgerItem> items = response.items();
        assertThat(items).extracting(AdminLedgerItem::balanceBefore).containsExactly(12000L, 12000L, 22000L);
        assertThat(items).extracting(AdminLedgerItem::balanceAfter).containsExactly(12200L, 12000L, 12000L);
        assertThat(items.get(1).serveId()).isEqualTo(serveId);
        assertThat(items.get(2).pgTid()).isEqualTo("tgen_1");
        assertThat(items.get(2).createdAt().toString()).isEqualTo("2026-10-01T00:00:05+09:00");
        assertThat(response.nextPageToken()).isNull();
    }

    @Test
    void 조건에_맞는_줄이_없으면_누적을_계산하지_않는다() {
        when(repository.findAdminLedger(any())).thenReturn(List.of());

        CursorPageResponse<AdminLedgerItem> response = service.search(OWNER, null, DAY, DAY, null, null, 20, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.nextPageToken()).isNull();
        verify(repository, never()).sumBalanceBefore(any(), any());
    }

    @Test
    void 조건은_기간의_범위와_종류_캠페인_정렬_위치로_넘긴다() {
        when(repository.findAdminLedger(any())).thenReturn(List.of());

        String token = PageTokens.encode(new PageCursor(10L, DEDUCTED_AT.toString()));
        service.search(OWNER, EntryType.RESERVE, DAY, LocalDate.of(2026, 10, 3), 12L, token, 20, "ASC");

        AdminLedgerCondition condition = captureCondition();
        assertThat(condition.ownerId()).isEqualTo(OWNER);
        assertThat(condition.type()).isEqualTo(EntryType.RESERVE);
        assertThat(condition.fromInclusive()).isEqualTo(START);
        assertThat(condition.toExclusive()).isEqualTo(LocalDateTime.of(2026, 10, 4, 0, 0));
        assertThat(condition.campaignId()).isEqualTo(12L);
        assertThat(condition.ascending()).isTrue();
        assertThat(condition.cursorCreatedAt()).isEqualTo(DEDUCTED_AT);
        assertThat(condition.cursorId()).isEqualTo(10L);
        assertThat(condition.limit()).isEqualTo(21);
    }

    @Test
    void 정렬_방향을_주지_않거나_desc면_내림차순이다() {
        when(repository.findAdminLedger(any())).thenReturn(List.of());

        service.search(OWNER, null, DAY, DAY, null, null, 20, "desc");
        service.search(OWNER, null, DAY, DAY, null, null, 20, null);

        ArgumentCaptor<AdminLedgerCondition> captor = ArgumentCaptor.forClass(AdminLedgerCondition.class);
        verify(repository, org.mockito.Mockito.times(2)).findAdminLedger(captor.capture());
        assertThat(captor.getAllValues()).extracting(AdminLedgerCondition::ascending).containsExactly(false, false);
    }

    @Test
    void 알_수_없는_정렬_방향은_거절한다() {
        assertThatThrownBy(() -> service.search(OWNER, null, DAY, DAY, null, null, 20, "createdAt"))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 페이지_크기는_기본_20_최대_100으로_맞춘다() {
        when(repository.findAdminLedger(any())).thenReturn(List.of());

        service.search(OWNER, null, DAY, DAY, null, null, 0, null);
        service.search(OWNER, null, DAY, DAY, null, null, 1000, null);

        ArgumentCaptor<AdminLedgerCondition> captor = ArgumentCaptor.forClass(AdminLedgerCondition.class);
        verify(repository, org.mockito.Mockito.times(2)).findAdminLedger(captor.capture());
        assertThat(captor.getAllValues()).extracting(AdminLedgerCondition::limit).containsExactly(21, 101);
    }

    @Test
    void 점주_ID가_없으면_질의하지_않고_거절한다() {
        assertThatThrownBy(() -> service.search(null, null, DAY, DAY, null, null, 20, null))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));

        verify(repository, never()).findAdminLedger(any());
    }

    @Test
    void 토큰의_시각을_읽을_수_없으면_첫_페이지로_조회한다() {
        when(repository.findAdminLedger(any())).thenReturn(List.of());
        String token = PageTokens.encode(new PageCursor(12L, "not-a-date"));

        service.search(OWNER, null, DAY, DAY, null, token, 20, null);

        AdminLedgerCondition condition = captureCondition();
        assertThat(condition.cursorCreatedAt()).isNull();
        assertThat(condition.cursorId()).isNull();
    }

    @Test
    void 기간이_없거나_뒤집혔거나_31일을_넘으면_질의하지_않고_거절한다() {
        assertThatThrownBy(() -> service.search(OWNER, null, null, DAY, null, null, 20, null))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> service.search(OWNER, null, DAY, DAY.minusDays(1), null, null, 20, null))
                .isInstanceOf(BillingException.class);
        assertThatThrownBy(() -> service.search(OWNER, null, DAY, DAY.plusDays(31), null, null, 20, null))
                .isInstanceOf(BillingException.class);

        verify(repository, never()).findAdminLedger(any());
    }

    private AdminLedgerRow row(long id, LocalDateTime at) {
        return new AdminLedgerRow(id, OWNER, EntryType.DEDUCT, -10L, 12L, null, null,
                LedgerSource.REALTIME, null, at);
    }

    @Test
    void 다음_페이지가_있으면_마지막으로_반환한_행의_토큰을_만든다() {
        when(repository.findAdminLedger(any())).thenReturn(List.of(
                row(13L, RELEASED_AT), row(12L, DEDUCTED_AT), row(11L, DEDUCTED_AT)));

        CursorPageResponse<AdminLedgerItem> response = service.search(OWNER, null, DAY, DAY, null, null, 2, null);

        assertThat(response.items()).extracting(AdminLedgerItem::ledgerId).containsExactly(13L, 12L);
        assertThat(PageTokens.decode(response.nextPageToken())).isEqualTo(new PageCursor(12L, DEDUCTED_AT.toString()));
    }

    @Test
    void 요청한_크기와_행_수가_같으면_마지막_페이지다() {
        when(repository.findAdminLedger(any())).thenReturn(List.of(row(12L, DEDUCTED_AT), row(11L, DEDUCTED_AT)));

        CursorPageResponse<AdminLedgerItem> response = service.search(OWNER, null, DAY, DAY, null, null, 2, null);

        assertThat(response.items()).hasSize(2);
        assertThat(response.nextPageToken()).isNull();
    }

    @Test
    void 토큰의_정렬값이_없으면_첫_페이지로_조회한다() {
        when(repository.findAdminLedger(any())).thenReturn(List.of());
        String token = PageTokens.encode(new PageCursor(12L, null));

        service.search(OWNER, null, DAY, DAY, null, token, 20, null);

        AdminLedgerCondition condition = captureCondition();
        assertThat(condition.cursorCreatedAt()).isNull();
        assertThat(condition.cursorId()).isNull();
    }
}
