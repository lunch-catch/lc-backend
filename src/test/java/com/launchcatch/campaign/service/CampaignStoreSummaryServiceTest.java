package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.CampaignStatus;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.repository.CampaignRepository;
import com.launchcatch.campaign.repository.CampaignSummaryRow;
import com.launchcatch.store.contract.StoreCampaignStatus;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdate;
import com.launchcatch.store.contract.StoreCampaignSummaryUpdater;
import com.launchcatch.store.contract.StoreDiscountTargetType;
import com.launchcatch.store.contract.StoreDiscountType;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class CampaignStoreSummaryServiceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 1);

    @Mock
    private CampaignRepository campaignRepository;
    @Mock
    private StoreCampaignSummaryUpdater storeCampaignSummaryUpdater;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Captor
    private ArgumentCaptor<List<StoreCampaignSummaryUpdate>> updatesCaptor;

    private CampaignStoreSummaryService service;

    @BeforeEach
    void setUp() {
        service = new CampaignStoreSummaryService(campaignRepository, storeCampaignSummaryUpdater, transactionTemplate);
        // 청크마다 자기 트랜잭션으로 저장한다. 목은 콜백을 그대로 호출 스레드에서 돌린다.
        lenient().doAnswer(invocation -> {
            Consumer<Object> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private CampaignSummaryRow row(long campaignId, long storeId) {
        return new CampaignSummaryRow(storeId, campaignId, CampaignStatus.ACTIVE, DiscountTargetType.ALL,
                DiscountType.AMOUNT, 3000, null, LocalTime.of(11, 0), LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("ACTIVE 캠페인의 요약을 새 영업일 몫으로 저장한다")
    void ACTIVE_캠페인의_요약을_저장한다() {
        when(campaignRepository.findActiveSummaryRowsAfter(eq(0L), eq(BUSINESS_DATE), any(Pageable.class)))
                .thenReturn(List.of(row(1L, 10L), row(2L, 20L)));
        when(campaignRepository.findActiveSummaryRowsAfter(eq(2L), eq(BUSINESS_DATE), any(Pageable.class))).thenReturn(List.of());

        int prepared = service.prepare(BUSINESS_DATE);

        assertThat(prepared).isEqualTo(2);
        verify(storeCampaignSummaryUpdater).updateAll(updatesCaptor.capture());
        List<StoreCampaignSummaryUpdate> updates = updatesCaptor.getValue();
        assertThat(updates).extracting(StoreCampaignSummaryUpdate::campaignId).containsExactly(1L, 2L);
        assertThat(updates).extracting(StoreCampaignSummaryUpdate::storeId).containsExactly(10L, 20L);
        assertThat(updates.get(0).command().businessDate()).isEqualTo(BUSINESS_DATE);
        assertThat(updates.get(0).command().campaignStatus()).isEqualTo(StoreCampaignStatus.ACTIVE);
        assertThat(updates.get(0).command().discountTargetType()).isEqualTo(StoreDiscountTargetType.ALL);
        assertThat(updates.get(0).command().discountType()).isEqualTo(StoreDiscountType.AMOUNT);
        assertThat(updates.get(0).command().discountValue()).isEqualTo(3000);
    }

    @Test
    @DisplayName("대상 조회에 영업일을 넘겨 집행 기간이 끝난 ACTIVE 캠페인을 요약에서 뺀다(#871)")
    void 조회에_영업일을_넘겨_만료된_캠페인을_뺀다() {
        LocalDate businessDate = LocalDate.of(2026, 11, 9);
        when(campaignRepository.findActiveSummaryRowsAfter(eq(0L), eq(businessDate), any(Pageable.class)))
                .thenReturn(List.of(row(1L, 10L)));
        when(campaignRepository.findActiveSummaryRowsAfter(eq(1L), eq(businessDate), any(Pageable.class)))
                .thenReturn(List.of());

        int prepared = service.prepare(businessDate);

        assertThat(prepared).isEqualTo(1);
        verify(campaignRepository).findActiveSummaryRowsAfter(eq(0L), eq(businessDate), any(Pageable.class));
        verify(campaignRepository).findActiveSummaryRowsAfter(eq(1L), eq(businessDate), any(Pageable.class));
        verify(storeCampaignSummaryUpdater).updateAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue().get(0).command().businessDate()).isEqualTo(businessDate);
    }

    @Test
    @DisplayName("ACTIVE 캠페인이 없으면 요약 저장을 부르지 않는다")
    void 대상이_없으면_저장을_부르지_않는다() {
        when(campaignRepository.findActiveSummaryRowsAfter(eq(0L), eq(BUSINESS_DATE), any(Pageable.class))).thenReturn(List.of());

        assertThat(service.prepare(BUSINESS_DATE)).isZero();

        verify(storeCampaignSummaryUpdater, never()).updateAll(anyList());
    }

    @Test
    @DisplayName("청크를 넘으면 마지막 id 부터 다음 청크를 읽어 청크마다 저장한다")
    void 청크를_넘으면_다음_청크를_이어서_저장한다() {
        List<CampaignSummaryRow> first = IntStream.rangeClosed(1, 500).mapToObj(id -> row(id, id)).toList();
        when(campaignRepository.findActiveSummaryRowsAfter(eq(0L), eq(BUSINESS_DATE), any(Pageable.class))).thenReturn(first);
        when(campaignRepository.findActiveSummaryRowsAfter(eq(500L), eq(BUSINESS_DATE), any(Pageable.class)))
                .thenReturn(List.of(row(501L, 501L)));
        when(campaignRepository.findActiveSummaryRowsAfter(eq(501L), eq(BUSINESS_DATE), any(Pageable.class))).thenReturn(List.of());

        int prepared = service.prepare(BUSINESS_DATE);

        assertThat(prepared).isEqualTo(501);
        verify(storeCampaignSummaryUpdater, times(2)).updateAll(updatesCaptor.capture());
        assertThat(updatesCaptor.getAllValues().get(0)).hasSize(500);
        assertThat(updatesCaptor.getAllValues().get(1)).hasSize(1);
    }

    @Test
    @DisplayName("청크가 데이터 오류로 실패하면 그 청크만 한 건씩 다시 저장하고 오류 난 캠페인만 건너뛴다(#903)")
    void 데이터_오류_캠페인만_건너뛰고_나머지를_저장한다() {
        when(campaignRepository.findActiveSummaryRowsAfter(eq(0L), eq(BUSINESS_DATE), any(Pageable.class)))
                .thenReturn(List.of(row(1L, 10L), row(2L, 20L), row(3L, 30L)));
        when(campaignRepository.findActiveSummaryRowsAfter(eq(3L), eq(BUSINESS_DATE), any(Pageable.class))).thenReturn(List.of());
        // 세 건을 한 번에 저장하면 실패하고, 한 건씩 저장하면 2번 캠페인만 실패한다.
        doThrow(new IllegalArgumentException("할인 대상 메뉴가 해당 가게에 속하지 않습니다."))
                .when(storeCampaignSummaryUpdater).updateAll(argThat(updates -> updates.size() == 3));
        doThrow(new IllegalArgumentException("할인 대상 메뉴가 해당 가게에 속하지 않습니다."))
                .when(storeCampaignSummaryUpdater).updateAll(argThat(updates -> updates.size() == 1
                        && updates.get(0).campaignId() == 2L));

        int prepared = service.prepare(BUSINESS_DATE);

        assertThat(prepared).isEqualTo(2);
        verify(storeCampaignSummaryUpdater, times(4)).updateAll(updatesCaptor.capture());
        List<List<StoreCampaignSummaryUpdate>> calls = updatesCaptor.getAllValues();
        assertThat(calls.get(0)).hasSize(3);
        assertThat(calls.subList(1, 4)).allSatisfy(call -> assertThat(call).hasSize(1));
        assertThat(calls.subList(1, 4)).extracting(call -> call.get(0).campaignId()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("한 청크가 실패해도 다른 청크는 청크 단위로 그대로 저장한다(#903)")
    void 실패한_청크만_한_건씩_다시_저장한다() {
        List<CampaignSummaryRow> first = IntStream.rangeClosed(1, 500).mapToObj(id -> row(id, id)).toList();
        when(campaignRepository.findActiveSummaryRowsAfter(eq(0L), eq(BUSINESS_DATE), any(Pageable.class))).thenReturn(first);
        when(campaignRepository.findActiveSummaryRowsAfter(eq(500L), eq(BUSINESS_DATE), any(Pageable.class)))
                .thenReturn(List.of(row(501L, 501L)));
        when(campaignRepository.findActiveSummaryRowsAfter(eq(501L), eq(BUSINESS_DATE), any(Pageable.class))).thenReturn(List.of());
        doThrow(new IllegalArgumentException("데이터 오류")).when(storeCampaignSummaryUpdater)
                .updateAll(argThat(updates -> updates.size() == 500));
        doThrow(new IllegalArgumentException("데이터 오류")).when(storeCampaignSummaryUpdater)
                .updateAll(argThat(updates -> updates.size() == 1 && updates.get(0).campaignId() == 7L));

        int prepared = service.prepare(BUSINESS_DATE);

        // 첫 청크 500건 중 7번만 빠지고 둘째 청크 1건은 그대로 한 번에 저장된다.
        assertThat(prepared).isEqualTo(500);
        verify(storeCampaignSummaryUpdater, times(1 + 500 + 1)).updateAll(anyList());
    }

    @Test
    @DisplayName("데이터 오류가 아닌 실패는 그대로 올려 단계를 실패시킨다(#903)")
    void 데이터_오류가_아닌_실패는_예외를_올린다() {
        when(campaignRepository.findActiveSummaryRowsAfter(eq(0L), eq(BUSINESS_DATE), any(Pageable.class)))
                .thenReturn(List.of(row(1L, 10L)));
        doThrow(new IllegalStateException("db down")).when(storeCampaignSummaryUpdater).updateAll(anyList());

        assertThatThrownBy(() -> service.prepare(BUSINESS_DATE)).isInstanceOf(IllegalStateException.class);
        verify(storeCampaignSummaryUpdater, times(1)).updateAll(anyList());
    }
}
