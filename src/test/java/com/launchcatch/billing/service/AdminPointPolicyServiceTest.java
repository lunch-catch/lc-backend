package com.launchcatch.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.billing.dto.AdminPointPolicyItem;
import com.launchcatch.billing.dto.AdminPointPolicyResponse;
import com.launchcatch.billing.dto.PointPolicySaveRequest;
import com.launchcatch.billing.entity.PointPolicy;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.billing.repository.PointPolicyRepository;
import com.launchcatch.global.config.ClockConfig;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.global.response.PageResponse;
import com.launchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class AdminPointPolicyServiceTest {

    /*
     * 서울 기준 2026-10-15 00:30 이다.
     * UTC 로는 아직 10-14 라서 날짜를 UTC 로 계산하면 내일이 하루 어긋난다.
     */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T15:30:00Z"), ClockConfig.ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 16);
    private static final Long ADMIN = 2L;

    private final PointPolicyRepository repository = mock(PointPolicyRepository.class);
    private final OwnerPointPolicyService ownerPolicyService = mock(OwnerPointPolicyService.class);
    private final AuditLogWriter auditLogWriter = mock(AuditLogWriter.class);
    private final AdminPointPolicyService service =
            new AdminPointPolicyService(repository, ownerPolicyService, auditLogWriter, CLOCK);

    private static PointPolicy policy(LocalDate date, long min, List<Long> products, String reason, Long createdBy) {
        return PointPolicy.effectiveFrom(date, min, products, reason, createdBy);
    }

    private static PointPolicySaveRequest request(Long min, List<Long> products, String reason) {
        return new PointPolicySaveRequest(min, products, reason);
    }

    @Test
    void 현재_정책과_서버_시계로_정한_내일_적용분을_함께_돌려준다() {
        when(ownerPolicyService.currentPolicy())
                .thenReturn(policy(LocalDate.of(2026, 1, 1), 10000L, List.of(10000L), "최초", null));
        when(repository.findByEffectiveDate(TOMORROW))
                .thenReturn(Optional.of(policy(TOMORROW, 20000L, List.of(20000L, 50000L), "인상", ADMIN)));

        AdminPointPolicyResponse response = service.getPolicy();

        assertThat(response.current().minChargeAmount()).isEqualTo(10000L);
        assertThat(response.current().createdBy()).isNull();
        assertThat(response.next().effectiveDate()).isEqualTo(TOMORROW);
        assertThat(response.next().chargeProducts()).containsExactly(20000L, 50000L);
        assertThat(response.next().changeReason()).isEqualTo("인상");
        assertThat(response.next().createdBy()).isEqualTo(ADMIN);
    }

    @Test
    void 내일_적용분이_없으면_next는_null이다() {
        when(ownerPolicyService.currentPolicy())
                .thenReturn(policy(LocalDate.of(2026, 1, 1), 10000L, List.of(10000L), null, null));
        when(repository.findByEffectiveDate(TOMORROW)).thenReturn(Optional.empty());

        assertThat(service.getPolicy().next()).isNull();
    }

    @Test
    void 이력은_적용일_내림차순_페이지로_돌려준다() {
        PointPolicy saved = policy(TODAY, 10000L, List.of(10000L), "조정", ADMIN);
        when(repository.findAllByOrderByEffectiveDateDesc(any()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(saved), invocation.getArgument(0), 41));

        PageResponse<AdminPointPolicyItem> response = service.history(2, 20);

        assertThat(response.items()).extracting(AdminPointPolicyItem::effectiveDate).containsExactly(TODAY);
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(41);
    }

    @Test
    void 이력의_크기는_1_미만이면_20으로_100을_넘으면_100으로_보정한다() {
        when(repository.findAllByOrderByEffectiveDateDesc(any()))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(0), 0));

        service.history(0, 0);
        service.history(0, 500);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, org.mockito.Mockito.times(2)).findAllByOrderByEffectiveDateDesc(captor.capture());
        assertThat(captor.getAllValues()).extracting(Pageable::getPageSize).containsExactly(20, 100);
        assertThat(captor.getAllValues().get(0)).isEqualTo(PageRequest.of(0, 20));
    }

    @Test
    void 이력의_페이지_번호가_음수면_거절한다() {
        assertThatThrownBy(() -> service.history(-1, 20))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));
    }

    @Test
    void 저장은_서버_시계로_정한_내일_날짜로_덮어쓰고_변경_전후를_감사_로그에_남긴다() {
        when(repository.findByEffectiveDate(TOMORROW))
                .thenReturn(Optional.of(policy(TOMORROW, 10000L, List.of(10000L, 30000L), "이전", 3L)));

        AdminPointPolicyItem saved = service.save(ADMIN, request(20000L, List.of(20000L, 50000L), "인상"));

        verify(repository).upsert(TOMORROW, 20000L, "[20000,50000]", "인상", ADMIN,
                LocalDateTime.of(2026, 10, 15, 0, 30));
        verify(auditLogWriter).write(ADMIN, AdminPointPolicyService.AUDIT_ACTION, "2026-10-16",
                "before=minChargeAmount=10000,chargeProducts=[10000,30000],reason=이전"
                        + ";after=minChargeAmount=20000,chargeProducts=[20000,50000],reason=인상");
        assertThat(saved.effectiveDate()).isEqualTo(TOMORROW);
        assertThat(saved.minChargeAmount()).isEqualTo(20000L);
        assertThat(saved.createdBy()).isEqualTo(ADMIN);
    }

    @Test
    void 내일_적용분이_없으면_현재_정책을_변경_전_값으로_남긴다() {
        PointPolicy current = policy(LocalDate.of(2026, 1, 1), 10000L, List.of(10000L), "최초", null);
        // DB 에서 읽은 JSON 배열은 작은 수가 Integer 로 들어올 수 있다
        ReflectionTestUtils.setField(current, "chargeProducts", List.of(10000));
        when(repository.findByEffectiveDate(TOMORROW)).thenReturn(Optional.empty());
        when(ownerPolicyService.currentPolicy()).thenReturn(current);

        service.save(ADMIN, request(10000L, List.of(10000L), null));

        verify(auditLogWriter).write(eq(ADMIN), eq(AdminPointPolicyService.AUDIT_ACTION), eq("2026-10-16"),
                eq("before=minChargeAmount=10000,chargeProducts=[10000],reason=최초"
                        + ";after=minChargeAmount=10000,chargeProducts=[10000],reason=null"));
    }

    @Test
    void 상품이_최소_충전_금액보다_작으면_저장하지_않고_거절한다() {
        assertThatThrownBy(() -> service.save(ADMIN, request(20000L, List.of(10000L), null)))
                .isInstanceOfSatisfying(BillingException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT));

        verify(repository, never()).upsert(any(), anyLong(), anyString(), any(), any(), any());
        verify(auditLogWriter, never()).write(any(), any(), any(), any());
    }
}
