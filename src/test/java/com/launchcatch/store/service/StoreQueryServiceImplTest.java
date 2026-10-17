package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.launchcatch.store.contract.StoreAdCandidateInfo;
import com.launchcatch.store.contract.StoreQueryService;
import com.launchcatch.store.dto.StoreAdCandidateQueryRow;
import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.dto.StoreMenuQueryRow;
import com.launchcatch.store.dto.StoreQueryRow;
import com.launchcatch.store.dto.StoreSummaryQueryRow;
import com.launchcatch.store.repository.StoreQueryRepository;
import com.launchcatch.store.repository.OwnerStoreQueryRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class StoreQueryServiceImplTest {
    private final StoreQueryRepository repository = mock(StoreQueryRepository.class);
    private final StoreQueryServiceImpl service = new StoreQueryServiceImpl(repository, mock(OwnerStoreQueryRepository.class), "https://cdn.example.com/");

    @Test
    void 광고_후보는_업종과_LOGO_URL과_DECIMAL_좌표를_반환한다() {
        var latitude = new BigDecimal("37.1234567");
        var longitude = new BigDecimal("127.1234567");
        when(repository.findAdCandidatesByStoreIds(List.of(1L, 2L))).thenReturn(List.of(
                new StoreAdCandidateQueryRow(1L, "국밥집", "KOREAN", "stores/로고 #1.png", latitude, longitude),
                new StoreAdCandidateQueryRow(2L, "카페", "CAFE_DESSERT", "stores/2.png", BigDecimal.ZERO, BigDecimal.ZERO)));

        assertThat(service.findAdCandidatesByStoreIds(List.of(1L, 2L))).containsExactly(
                new StoreAdCandidateInfo(1L, "국밥집", "KOREAN",
                        "https://cdn.example.com/stores/%EB%A1%9C%EA%B3%A0%20%231.png", latitude, longitude),
                new StoreAdCandidateInfo(2L, "카페", "CAFE_DESSERT", "https://cdn.example.com/stores/2.png",
                        BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void 광고_후보의_빈_입력은_DB_조회_없이_빈_목록을_반환한다() {
        assertThat(service.findAdCandidatesByStoreIds(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void 광고_후보_조회_장애는_빈_목록으로_숨기지_않는다() {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.findAdCandidatesByStoreIds(List.of(1L))).thenThrow(failure);

        assertThatThrownBy(() -> service.findAdCandidatesByStoreIds(List.of(1L))).isSameAs(failure);
    }

    @Test
    void 광고_후보의_검증된_LOGO가_없으면_URL은_null이다() {
        when(repository.findAdCandidatesByStoreIds(List.of(1L))).thenReturn(List.of(
                new StoreAdCandidateQueryRow(1L, "가게", "KOREAN", null,
                        new BigDecimal("37.1234567"), new BigDecimal("127.1234567"))));

        assertThat(service.findAdCandidatesByStoreIds(List.of(1L))).singleElement()
                .satisfies(store -> assertThat(store.storeImageUrl()).isNull());
    }

    @Test
    void 광고_후보는_상한까지_허용하고_초과하면_DB_조회_전에_거절한다() {
        var ids = java.util.Collections.nCopies(StoreQueryService.MAX_AD_CANDIDATE_STORE_IDS, 1L);
        when(repository.findAdCandidatesByStoreIds(ids)).thenReturn(List.of(
                new StoreAdCandidateQueryRow(1L, "가게", "KOREAN", null,
                        BigDecimal.ONE, BigDecimal.ONE)));

        assertThat(service.findAdCandidatesByStoreIds(ids)).hasSize(1);
        clearInvocations(repository);
        assertThatIllegalArgumentException().isThrownBy(() -> service.findAdCandidatesByStoreIds(
                java.util.Collections.nCopies(StoreQueryService.MAX_AD_CANDIDATE_STORE_IDS + 1, 1L)));
        verifyNoInteractions(repository);
    }

    @Test
    void 점주_요약은_등록_여부와_마스킹한_번호를_반환한다() {
        when(repository.findSummariesByOwnerIds(List.of(1L, 2L))).thenReturn(List.of(
                new StoreSummaryQueryRow(1, 10, "가게", true, "1234567890"),
                new StoreSummaryQueryRow(2, 20, "등록 중", false, null)));
        assertThat(service.findSummariesByOwnerIds(List.of(1L, 2L)))
                .extracting("ownerId", "storeId", "name", "finalized", "businessRegistrationNumber")
                .containsExactly(tuple(1L, 10L, "가게", true, "123**67890"),
                        tuple(2L, 20L, "등록 중", false, null));
        var ownerIds = java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList();
        when(repository.findSummariesByOwnerIds(ownerIds)).thenReturn(List.of(
                new StoreSummaryQueryRow(100, 1000, "백 번째 가게", false, null)));
        assertThat(service.findSummariesByOwnerIds(ownerIds)).singleElement()
                .satisfies(summary -> assertThat(summary.ownerId()).isEqualTo(100));
        assertThatIllegalArgumentException().isThrownBy(() -> service.findSummariesByOwnerIds(
                java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList()))
                .withMessage("점주 ID는 최대 100개까지 조회할 수 있습니다.");
    }

    @Test
    void 가게의_이미지와_현재_좌표를_반환한다() {
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                new StoreQueryRow(1L, "가게", "stores/로고 #1.png", new BigDecimal("37.1234567"),
                        new BigDecimal("127.1234567")),
                new StoreQueryRow(2L, "좌표 누락 가게", "stores/2.png", null, null)));

        assertThat(service.findAllById(List.of(1L, 2L))).containsExactly(
                new StoreInfo(1L, "가게", "https://cdn.example.com/stores/%EB%A1%9C%EA%B3%A0%20%231.png",
                        37.1234567, 127.1234567),
                new StoreInfo(2L, "좌표 누락 가게", "https://cdn.example.com/stores/2.png", null, null));
    }

    @Test
    void 메뉴의_소속_가게와_할인_전_가격과_이미지를_반환한다() {
        when(repository.findMenusByIdIn(List.of(10L, 11L))).thenReturn(List.of(
                new StoreMenuQueryRow(10L, 1L, "menus/10.png", "국밥", 9000L),
                new StoreMenuQueryRow(11L, 1L, "menus/11.png", "서비스", 0L)));

        assertThat(service.findMenusByIdIn(List.of(10L, 11L))).containsExactly(
                new StoreMenuInfo(10L, 1L, "https://cdn.example.com/menus/10.png", "국밥", 9000L),
                new StoreMenuInfo(11L, 1L, "https://cdn.example.com/menus/11.png", "서비스", 0L));
    }

    @Test
    void 빈_입력과_누락된_ID는_빈_목록이다() {
        when(repository.findAllById(List.of(99L))).thenReturn(List.of());
        when(repository.findMenusByIdIn(List.of(99L))).thenReturn(List.of());

        assertThat(service.findAllById(List.of())).isEmpty();
        assertThat(service.findMenusByIdIn(List.of())).isEmpty();
        assertThat(service.findAllById(List.of(99L))).isEmpty();
        assertThat(service.findMenusByIdIn(List.of(99L))).isEmpty();
    }

    @Test
    void 관리_관계_판정을_그대로_반환한다() {
        when(repository.canManageStore(1L, 10L)).thenReturn(true);
        when(repository.canManageStore(2L, 10L)).thenReturn(false);

        assertThat(service.canManageStore(1L, 10L)).isTrue();
        assertThat(service.canManageStore(2L, 10L)).isFalse();
    }

    @Test
    void 가게_조회_장애는_빈_목록으로_숨기지_않는다() {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.findAllById(List.of(1L))).thenThrow(failure);

        assertThatThrownBy(() -> service.findAllById(List.of(1L))).isSameAs(failure);
    }

    @Test
    void 메뉴_조회_장애는_빈_목록으로_숨기지_않는다() {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.findMenusByIdIn(List.of(1L))).thenThrow(failure);

        assertThatThrownBy(() -> service.findMenusByIdIn(List.of(1L))).isSameAs(failure);
    }

    @Test
    void 관리_관계_조회_장애는_false로_숨기지_않는다() {
        var failure = new DataAccessResourceFailureException("DB unavailable");
        when(repository.canManageStore(1L, 10L)).thenThrow(failure);

        assertThatThrownBy(() -> service.canManageStore(1L, 10L)).isSameAs(failure);
    }

    @Test
    void 이미지가_있는데_CDN_설정이_없으면_실패한다() {
        var unconfigured = new StoreQueryServiceImpl(repository, mock(OwnerStoreQueryRepository.class), "");
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(
                new StoreQueryRow(1L, "가게", "stores/logo.png", null, null)));

        assertThatThrownBy(() -> unconfigured.findAllById(List.of(1L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 최종_등록된_가게의_대표_이미지가_없으면_실패한다() {
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(
                new StoreQueryRow(1L, "가게", null, null, null)));

        assertThatThrownBy(() -> service.findAllById(List.of(1L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미지가 누락");
    }

    @Test
    void 최종_등록된_가게의_메뉴_이미지가_없으면_실패한다() {
        when(repository.findMenusByIdIn(List.of(10L))).thenReturn(List.of(
                new StoreMenuQueryRow(10L, 1L, null, "국밥", 9000L)));

        assertThatThrownBy(() -> service.findMenusByIdIn(List.of(10L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미지가 누락");
    }

    @Test
    void 이미지_저장_키가_공백이어도_실패한다() {
        when(repository.findMenusByIdIn(List.of(10L))).thenReturn(List.of(
                new StoreMenuQueryRow(10L, 1L, " ", "국밥", 9000L)));

        assertThatThrownBy(() -> service.findMenusByIdIn(List.of(10L)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("이미지가 누락");
    }

    @Test
    void 잘못된_목록_ID를_거절한다() {
        assertThatNullPointerException().isThrownBy(() -> service.findAllById(null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.findAllById(Arrays.asList(1L, null)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.findAllById(List.of(0L)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.findMenusByIdIn(List.of(-1L)));
    }

    @Test
    void 잘못된_관리_관계_ID를_거절한다() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.canManageStore(null, 1L));
        assertThatIllegalArgumentException().isThrownBy(() -> service.canManageStore(0L, 1L));
        assertThatIllegalArgumentException().isThrownBy(() -> service.canManageStore(1L, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.canManageStore(1L, -1L));
    }
}