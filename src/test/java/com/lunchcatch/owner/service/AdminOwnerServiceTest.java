package com.lunchcatch.owner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lunchcatch.owner.dto.AdminOwnerListQuery;
import com.lunchcatch.owner.entity.OwnerStatus;
import com.lunchcatch.owner.repository.AdminOwnerRepository;
import com.lunchcatch.owner.repository.AdminOwnerRow;
import com.lunchcatch.store.contract.StoreQueryService;
import com.lunchcatch.store.contract.StoreSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AdminOwnerServiceTest {

    @Mock AdminOwnerRepository owners;
    @Mock StoreQueryService stores;
    private AdminOwnerService service;
    private final LocalDateTime joinedAt = LocalDateTime.of(2026, 10, 1, 0, 0);

    @BeforeEach
    void setUp() {
        service = new AdminOwnerService(owners, stores);
    }

    @Test
    void 필터를_결합하고_가입일_종료일의_다음날_직전까지_조회한다() {
        var from = LocalDate.of(2026, 10, 1);
        var to = LocalDate.of(2026, 10, 31);
        var page = PageRequest.of(1, 20);
        var row = new AdminOwnerRow(7L, OwnerStatus.ACTIVE, joinedAt, joinedAt.plusHours(1));
        when(stores.findOwnerIdsByNameContaining("런치", 10000)).thenReturn(List.of(7L, 9L));
        when(owners.search(OwnerStatus.ACTIVE, from.atStartOfDay(), to.plusDays(1).atStartOfDay(),
                List.of(7L, 9L), page)).thenReturn(new PageImpl<>(List.of(row), page, 21));
        when(stores.findSummariesByOwnerIds(List.of(7L))).thenReturn(List.of(
                new StoreSummary(7, 70, "런치식당", true, "123-45-*****")));

        var result = service.search(new AdminOwnerListQuery(OwnerStatus.ACTIVE, from, to, "런치", 1));

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.ownerId()).isEqualTo(7);
            assertThat(item.storeName()).isEqualTo("런치식당");
            assertThat(item.storeRegistered()).isTrue();
            assertThat(item.businessNo()).isEqualTo("123-45-*****");
            assertThat(item.joinedAt().toString()).isEqualTo("2026-10-01T00:00+09:00");
            assertThat(item.lastLoginAt().toString()).isEqualTo("2026-10-01T01:00+09:00");
        });
    }

    @Test
    void 숫자만_있는_검색어는_상호_검색_없이_점주_ID와_정확히_일치시킨다() {
        when(owners.search(isNull(), isNull(), isNull(), eq(List.of(123L)), any()))
                .thenReturn(new PageImpl<>(List.of()));

        var result = service.search(query(" 123 "));

        assertThat(result.items()).isEmpty();
        verifyNoInteractions(stores);
        verify(owners).search(null, null, null, List.of(123L), PageRequest.of(0, 20));
    }

    @Test
    void ID_범위를_넘는_숫자는_일치하는_점주가_없다() {
        assertThat(service.search(query("9999999999999999999999999")).items()).isEmpty();
        verifyNoInteractions(owners, stores);
    }

    @Test
    void 숫자와_문자가_섞인_검색어는_상호로_검색하며_결과가_없으면_빈_페이지다() {
        when(stores.findOwnerIdsByNameContaining("123식당", 10000)).thenReturn(List.of());

        assertThat(service.search(query("123식당")).totalElements()).isZero();
        verifyNoInteractions(owners);
    }

    @Test
    void 가게가_없는_점주도_남기고_등록_전_가게와_등록을_마친_가게를_구분한다() {
        var rows = List.of(new AdminOwnerRow(1L, OwnerStatus.ONBOARDING, joinedAt, null),
                new AdminOwnerRow(2L, OwnerStatus.ONBOARDING, joinedAt, null),
                new AdminOwnerRow(3L, OwnerStatus.ACTIVE, joinedAt, null));
        when(owners.search(isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(rows));
        when(stores.findSummariesByOwnerIds(List.of(1L, 2L, 3L))).thenReturn(List.of(
                new StoreSummary(3, 30, "완료", true, "123-45-*****"),
                new StoreSummary(2, 20, "등록 중", false, null)));

        var items = service.search(query("   ")).items();

        assertThat(items).extracting("ownerId").containsExactly(1L, 2L, 3L);
        assertThat(items.getFirst().storeName()).isNull();
        assertThat(items.getFirst().businessNo()).isNull();
        assertThat(items.getFirst().storeRegistered()).isFalse();
        assertThat(items.get(1).storeName()).isEqualTo("등록 중");
        assertThat(items.get(1).storeRegistered()).isFalse();
        assertThat(items.get(2).storeRegistered()).isTrue();
    }

    @Test
    void 탈퇴_점주는_상호를_유지하고_번호를_모두_가리며_로그인_시각을_숨긴다() {
        var rows = List.of(new AdminOwnerRow(1L, OwnerStatus.WITHDRAWN, joinedAt, joinedAt),
                new AdminOwnerRow(2L, OwnerStatus.WITHDRAWN, joinedAt, joinedAt));
        when(owners.search(isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(rows));
        when(stores.findSummariesByOwnerIds(List.of(1L, 2L))).thenReturn(List.of(
                new StoreSummary(1, 10, "탈퇴 전 상호", true, "123-45-*****"),
                new StoreSummary(2, 20, "초안", false, null)));

        var items = service.search(query(null)).items();

        assertThat(items.getFirst().storeName()).isEqualTo("탈퇴 전 상호");
        assertThat(items.getFirst().businessNo()).isEqualTo("***-**-*****");
        assertThat(items.getFirst().lastLoginAt()).isNull();
        assertThat(items.get(1).businessNo()).isNull();
        assertThat(items.get(1).lastLoginAt()).isNull();
    }

    @Test
    void 마지막_페이지를_넘겨도_전체_건수를_유지하며_가게는_조회하지_않는다() {
        when(owners.search(isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(3, 20), 41));

        var result = service.search(new AdminOwnerListQuery(null, null, null, null, 3));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(41);
        verifyNoInteractions(stores);
    }

    @Test
    void 검색_결과_상한_초과와_저장소_장애를_빈_목록으로_숨기지_않는다() {
        when(stores.findOwnerIdsByNameContaining("가게", 10000))
                .thenThrow(new IllegalArgumentException("검색어를 좁혀 주세요."));
        assertThatIllegalArgumentException().isThrownBy(() -> service.search(query("가게")));
        when(owners.search(isNull(), isNull(), isNull(), isNull(), any()))
                .thenThrow(new DataAccessResourceFailureException("DB unavailable"));
        assertThatThrownBy(() -> service.search(query(null)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 가게_요약_장애도_정상_목록으로_바꾸지_않는다() {
        when(owners.search(isNull(), isNull(), isNull(), isNull(), any())).thenReturn(new PageImpl<>(
                List.of(new AdminOwnerRow(1L, OwnerStatus.ACTIVE, joinedAt, null))));
        when(stores.findSummariesByOwnerIds(List.of(1L)))
                .thenThrow(new DataAccessResourceFailureException("DB unavailable"));

        assertThatThrownBy(() -> service.search(query(null)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void 음수_페이지와_역전된_가입일_범위는_거부한다() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AdminOwnerListQuery(null, null, null, null, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new AdminOwnerListQuery(null,
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), null, 0));
    }

    private AdminOwnerListQuery query(String keyword) {
        return new AdminOwnerListQuery(null, null, null, keyword, 0);
    }
}
