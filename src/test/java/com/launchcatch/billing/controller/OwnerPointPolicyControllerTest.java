package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.PointPolicyResponse;
import com.launchcatch.billing.service.OwnerPointPolicyService;
import com.launchcatch.global.response.ResponseEnvelope;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnerPointPolicyControllerTest {

    private final OwnerPointPolicyService service = mock(OwnerPointPolicyService.class);
    private final OwnerPointPolicyController controller = new OwnerPointPolicyController(service);

    @Test
    void 오늘_정책을_성공_봉투에_담아_돌려준다() {
        PointPolicyResponse policy =
                new PointPolicyResponse(10000, List.of(10000L, 30000L), LocalDate.of(2026, 1, 1));
        when(service.getCurrent()).thenReturn(policy);

        ResponseEnvelope<PointPolicyResponse> response = controller.current();

        assertThat(response.code()).isEqualTo("SUCCESS");
        assertThat(response.data()).isSameAs(policy);
    }
}
