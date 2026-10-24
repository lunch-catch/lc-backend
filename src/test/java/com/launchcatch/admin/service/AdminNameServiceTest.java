package com.launchcatch.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.admin.dto.AdminIdName;
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
class AdminNameServiceTest {

    @Mock
    private AdminRepository adminRepository;

    private AdminNameService service;

    @BeforeEach
    void setUp() {
        service = new AdminNameService(adminRepository);
    }

    @Test
    @DisplayName("관리자 ID 여러 개를 한 번의 조회로 ID 별 이름으로 바꾼다")
    void 여러_ID의_이름을_한_번에_조회한다() {
        when(adminRepository.findNamesByIdIn(List.of(1L, 7L)))
                .thenReturn(List.of(new AdminIdName(1L, "홍길동"), new AdminIdName(7L, "김관리")));

        Map<Long, String> names = service.findNamesByIds(List.of(1L, 7L));

        assertThat(names).containsExactlyInAnyOrderEntriesOf(Map.of(1L, "홍길동", 7L, "김관리"));
        verify(adminRepository).findNamesByIdIn(List.of(1L, 7L));
    }

    @Test
    @DisplayName("null 과 중복 ID 는 빼고 조회하고, 행이 없는 ID 는 결과에서 빠진다")
    void null과_중복을_빼고_없는_ID는_결과에서_뺀다() {
        when(adminRepository.findNamesByIdIn(List.of(1L, 9L))).thenReturn(List.of(new AdminIdName(1L, "홍길동")));

        Map<Long, String> names = service.findNamesByIds(Arrays.asList(1L, null, 1L, 9L));

        assertThat(names).containsExactlyEntriesOf(Map.of(1L, "홍길동"));
    }

    @Test
    @DisplayName("같은 ID 의 행이 둘 와도 첫 이름을 쓰고 실패하지 않는다")
    void 같은_ID가_겹쳐도_실패하지_않는다() {
        when(adminRepository.findNamesByIdIn(List.of(1L)))
                .thenReturn(List.of(new AdminIdName(1L, "홍길동"), new AdminIdName(1L, "다른 이름")));

        assertThat(service.findNamesByIds(List.of(1L))).containsExactlyEntriesOf(Map.of(1L, "홍길동"));
    }

    @Test
    @DisplayName("입력이 null 이거나 비었거나 null 뿐이면 조회하지 않고 빈 맵을 돌려준다")
    void 입력이_없으면_조회하지_않는다() {
        assertThat(service.findNamesByIds(null)).isEmpty();
        assertThat(service.findNamesByIds(List.of())).isEmpty();
        assertThat(service.findNamesByIds(Arrays.asList((Long) null))).isEmpty();

        verify(adminRepository, never()).findNamesByIdIn(anyCollection());
    }
}
