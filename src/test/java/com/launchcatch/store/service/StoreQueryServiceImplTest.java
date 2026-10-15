package com.launchcatch.store.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.launchcatch.store.contract.StoreInfo;
import com.launchcatch.store.contract.StoreMenuInfo;
import com.launchcatch.store.dto.StoreMenuQueryRow;
import com.launchcatch.store.dto.StoreQueryRow;
import com.launchcatch.store.repository.StoreQueryRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class StoreQueryServiceImplTest {
    private final StoreQueryRepository repository = mock(StoreQueryRepository.class);
    private final StoreQueryServiceImpl service = new StoreQueryServiceImpl(repository, "https://cdn.example.com/");

    @Test
    void 가게의_이미지와_현재_좌표를_반환한다() {
        when(repository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                new StoreQueryRow(1L, "가게", "stores/로고 #1.png", new BigDecimal("37.1234567"),
                        new BigDecimal("127.1234567")),
                new StoreQueryRow(2L, "입점중", null, null, null)));

        assertThat(service.findAllById(List.of(1L, 2L))).containsExactly(
                new StoreInfo(1L, "가게", "https://cdn.example.com/stores/%EB%A1%9C%EA%B3%A0%20%231.png",
                        new BigDecimal("37.1234567"), new BigDecimal("127.1234567")),
                new StoreInfo(2L, "입점중", null, null, null));
    }

    @Test
    void 메뉴의_소속_가게와_할인_전_가격과_이미지를_반환한다() {
        when(repository.findMenusByIdIn(List.of(10L, 11L))).thenReturn(List.of(
                new StoreMenuQueryRow(10L, 1L, "menus/10.png", "국밥", 9000L),
                new StoreMenuQueryRow(11L, 1L, null, "서비스", 0L)));

        assertThat(service.findMenusByIdIn(List.of(10L, 11L))).containsExactly(
                new StoreMenuInfo(10L, 1L, "https://cdn.example.com/menus/10.png", "국밥", 9000L),
                new StoreMenuInfo(11L, 1L, null, "서비스", 0L));
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
        var unconfigured = new StoreQueryServiceImpl(repository, "");
        when(repository.findAllById(List.of(1L))).thenReturn(List.of(
                new StoreQueryRow(1L, "가게", "stores/logo.png", null, null)));

        assertThatThrownBy(() -> unconfigured.findAllById(List.of(1L)))
                .isInstanceOf(IllegalStateException.class);
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