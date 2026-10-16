package com.launchcatch.campaign.template.service;

import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("소프트삭제된 버전을 지우고 지운 행 수를 돌려준다")
    void 소프트삭제된_버전을_지운다() {
        when(templateVersionRepository.deleteAllByDeletedAtIsNotNull()).thenReturn(3);

        int result = service.purgeSoftDeleted();

        assertThat(result).isEqualTo(3);
    }
}
