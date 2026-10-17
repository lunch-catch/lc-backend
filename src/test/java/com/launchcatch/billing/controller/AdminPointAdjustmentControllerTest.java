package com.launchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.auth.CustomUserDetails;
import com.launchcatch.auth.Role;
import com.launchcatch.billing.dto.PointAdjustmentRequest;
import com.launchcatch.billing.dto.PointAdjustmentResponse;
import com.launchcatch.billing.dto.PointAdjustmentResult;
import com.launchcatch.billing.service.PointAdjustmentService;
import com.launchcatch.global.response.ResponseEnvelope;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminPointAdjustmentControllerTest {

    private final PointAdjustmentService service = mock(PointAdjustmentService.class);
    private final AdminPointAdjustmentController controller = new AdminPointAdjustmentController(service);

    private static final CustomUserDetails ADMIN = new CustomUserDetails(2L, Role.ADMIN);
    private static final PointAdjustmentRequest REQUEST =
            new PointAdjustmentRequest("req-1", 7L, 1000L, "중복 과금 정정", null, null, null);
    private static final PointAdjustmentResponse ADJUSTMENT =
            new PointAdjustmentResponse(9101L, 7L, 1000L, null, null, "중복 과금 정정", OffsetDateTime.now());

    @Test
    void 새로_기록하면_201로_답한다() {
        when(service.adjust(2L, REQUEST)).thenReturn(new PointAdjustmentResult(ADJUSTMENT, true));

        ResponseEntity<ResponseEnvelope<PointAdjustmentResponse>> response = controller.adjust(ADMIN, REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().data()).isSameAs(ADJUSTMENT);
    }

    @Test
    void 같은_요청을_다시_받으면_처음_결과를_200으로_답한다() {
        when(service.adjust(2L, REQUEST)).thenReturn(new PointAdjustmentResult(ADJUSTMENT, false));

        ResponseEntity<ResponseEnvelope<PointAdjustmentResponse>> response = controller.adjust(ADMIN, REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data()).isSameAs(ADJUSTMENT);
    }

    @Test
    void 조정에는_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        PreAuthorize preAuthorize = AdminPointAdjustmentController.class
                .getMethod("adjust", CustomUserDetails.class, PointAdjustmentRequest.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");
    }
}
