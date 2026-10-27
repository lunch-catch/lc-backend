package com.lunchcatch.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lunchcatch.auth.CustomUserDetails;
import com.lunchcatch.auth.Role;
import com.lunchcatch.billing.dto.AdminPointPolicyItem;
import com.lunchcatch.billing.dto.AdminPointPolicyResponse;
import com.lunchcatch.billing.dto.PointPolicySaveRequest;
import com.lunchcatch.billing.service.AdminPointPolicyService;
import com.lunchcatch.global.response.PageResponse;
import com.lunchcatch.global.response.ResponseEnvelope;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminPointPolicyControllerTest {

    private final AdminPointPolicyService service = mock(AdminPointPolicyService.class);
    private final AdminPointPolicyController controller = new AdminPointPolicyController(service);

    private static final AdminPointPolicyItem ITEM =
            new AdminPointPolicyItem(10000L, List.of(10000L), LocalDate.of(2026, 10, 16), "조정", 2L);

    @Test
    void 정책_조회_결과를_그대로_감싼다() {
        AdminPointPolicyResponse policy = new AdminPointPolicyResponse(ITEM, null);
        when(service.getPolicy()).thenReturn(policy);

        assertThat(controller.getPolicy().data()).isSameAs(policy);
    }

    @Test
    void 이력_파라미터를_서비스에_그대로_넘긴다() {
        PageResponse<AdminPointPolicyItem> page = PageResponse.of(List.of(ITEM), 1, 20, 21);
        when(service.history(1, 20)).thenReturn(page);

        assertThat(controller.history(1, 20).data()).isSameAs(page);
    }

    @Test
    void 저장은_토큰의_관리자_ID로_서비스를_부른다() {
        PointPolicySaveRequest request = new PointPolicySaveRequest(10000L, List.of(10000L), "조정");
        when(service.save(2L, request)).thenReturn(ITEM);

        ResponseEnvelope<AdminPointPolicyItem> response =
                controller.saveNext(new CustomUserDetails(2L, Role.ADMIN), request);

        assertThat(response.data()).isSameAs(ITEM);
    }

    @Test
    void 세_메서드_모두_관리자_역할_검사가_붙어_있다() throws NoSuchMethodException {
        List<Method> methods = List.of(
                AdminPointPolicyController.class.getMethod("getPolicy"),
                AdminPointPolicyController.class.getMethod("history", int.class, int.class),
                AdminPointPolicyController.class.getMethod("saveNext",
                        CustomUserDetails.class, PointPolicySaveRequest.class));

        assertThat(methods).allSatisfy(method -> assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')"));
    }
}
