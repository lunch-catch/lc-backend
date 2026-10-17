package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.template.repository.TemplateVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemplateVersionPurgeServiceTest {

    @Mock private TemplateVersionRepository templateVersionRepository;

    private TemplateVersionPurgeService service;

    @BeforeEach
    void setUp() {
        service = new TemplateVersionPurgeService(templateVersionRepository);
    }

    @Test
    @DisplayName("지울 행이 없으면 한 번만 부르고 0을 돌려준다")
    void 지울_행이_없으면_한_번만_부른다() {
        when(templateVersionRepository.deleteBatchByDeletedAtIsNotNull(TemplateVersionPurgeService.BATCH_SIZE))
                .thenReturn(0);

        int result = service.purgeSoftDeleted();

        assertThat(result).isEqualTo(0);
        verify(templateVersionRepository, times(1))
                .deleteBatchByDeletedAtIsNotNull(TemplateVersionPurgeService.BATCH_SIZE);
    }

    @Test
    @DisplayName("한 청크보다 적게 남았으면 한 번만 부르고 그 건수를 돌려준다")
    void 한_청크보다_적으면_한_번만_부른다() {
        when(templateVersionRepository.deleteBatchByDeletedAtIsNotNull(TemplateVersionPurgeService.BATCH_SIZE))
                .thenReturn(237);

        int result = service.purgeSoftDeleted();

        assertThat(result).isEqualTo(237);
        verify(templateVersionRepository, times(1))
                .deleteBatchByDeletedAtIsNotNull(TemplateVersionPurgeService.BATCH_SIZE);
    }

    @Test
    @DisplayName("청크가 꽉 차서 나오면 다음 청크를 이어서 지우고 합친다")
    void 여러_청크를_이어서_지운다() {
        when(templateVersionRepository.deleteBatchByDeletedAtIsNotNull(TemplateVersionPurgeService.BATCH_SIZE))
                .thenReturn(TemplateVersionPurgeService.BATCH_SIZE, TemplateVersionPurgeService.BATCH_SIZE, 120);

        int result = service.purgeSoftDeleted();

        assertThat(result).isEqualTo(TemplateVersionPurgeService.BATCH_SIZE * 2 + 120);
        verify(templateVersionRepository, times(3))
                .deleteBatchByDeletedAtIsNotNull(TemplateVersionPurgeService.BATCH_SIZE);
    }
}
