package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.contract.AdminSummary;
import com.launchcatch.admin.dto.AdminIdSummary;
import com.launchcatch.admin.repository.AdminRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminSummaryServiceTest {

    @Mock
    private AdminRepository adminRepository;

    private AdminSummaryService service;

    @BeforeEach
    void setUp() {
        service = new AdminSummaryService(adminRepository);
    }

    @Test
    @DisplayName("관리자 ID 여러 개를 한 번의 조회로 ID 별 이름과 로그인 ID 로 바꾼다")
    void 여러_ID의_요약을_한_번에_조회한다() {
        when(adminRepository.findSummariesByIdIn(List.of(1L, 7L)))
                .thenReturn(List.of(new AdminIdSummary(1L, "홍길동", "admin01"), new AdminIdSummary(7L, "김관리", "admin07")));

        Map<Long, AdminSummary> admins = service.findSummariesByIds(List.of(1L, 7L));

        assertThat(admins).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, new AdminSummary("홍길동", "admin01"), 7L, new AdminSummary("김관리", "admin07")));
        verify(adminRepository).findSummariesByIdIn(List.of(1L, 7L));
    }

    @Test
    @DisplayName("이름이 같은 관리자도 로그인 ID 로 구분된다")
    void 이름이_같아도_로그인_ID로_구분된다() {
        when(adminRepository.findSummariesByIdIn(List.of(1L, 2L)))
                .thenReturn(List.of(new AdminIdSummary(1L, "홍길동", "admin01"), new AdminIdSummary(2L, "홍길동", "admin02")));

        Map<Long, AdminSummary> admins = service.findSummariesByIds(List.of(1L, 2L));

        assertThat(admins.get(1L).name()).isEqualTo(admins.get(2L).name());
        assertThat(admins.get(1L).loginId()).isNotEqualTo(admins.get(2L).loginId());
    }

    @Test
    @DisplayName("null 과 중복 ID 는 빼고 조회하고, 행이 없는 ID 는 결과에서 빠진다")
    void null과_중복을_빼고_없는_ID는_결과에서_뺀다() {
        when(adminRepository.findSummariesByIdIn(List.of(1L, 9L)))
                .thenReturn(List.of(new AdminIdSummary(1L, "홍길동", "admin01")));

        Map<Long, AdminSummary> admins = service.findSummariesByIds(Arrays.asList(1L, null, 1L, 9L));

        assertThat(admins).containsExactlyEntriesOf(Map.of(1L, new AdminSummary("홍길동", "admin01")));
    }

    @Test
    @DisplayName("같은 ID 의 행이 둘 와도 첫 요약을 쓰고 실패하지 않는다")
    void 같은_ID가_겹쳐도_실패하지_않는다() {
        when(adminRepository.findSummariesByIdIn(List.of(1L)))
                .thenReturn(List.of(new AdminIdSummary(1L, "홍길동", "admin01"), new AdminIdSummary(1L, "다른 이름", "other")));

        assertThat(service.findSummariesByIds(List.of(1L)))
                .containsExactlyEntriesOf(Map.of(1L, new AdminSummary("홍길동", "admin01")));
    }

    @Test
    @DisplayName("입력이 null 이거나 비었거나 null 뿐이면 조회하지 않고 빈 맵을 돌려준다")
    void 입력이_없으면_조회하지_않는다() {
        assertThat(service.findSummariesByIds(null)).isEmpty();
        assertThat(service.findSummariesByIds(List.of())).isEmpty();
        assertThat(service.findSummariesByIds(Arrays.asList((Long) null))).isEmpty();
        // 호출하는 쪽이 null ID 로 요약을 꺼내도 예외가 나지 않는다.
        assertThat(service.findSummariesByIds(Arrays.asList((Long) null)).get(null)).isNull();

        verify(adminRepository, never()).findSummariesByIdIn(anyCollection());
    }
}
