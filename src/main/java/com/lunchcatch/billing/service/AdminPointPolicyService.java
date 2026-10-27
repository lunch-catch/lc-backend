package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.AdminPointPolicyItem;
import com.lunchcatch.billing.dto.AdminPointPolicyResponse;
import com.lunchcatch.billing.dto.PointPolicySaveRequest;
import com.lunchcatch.billing.entity.PointPolicy;
import com.lunchcatch.billing.exception.BillingException;
import com.lunchcatch.billing.repository.PointPolicyRepository;
import com.lunchcatch.global.exception.CommonErrorCode;
import com.lunchcatch.global.response.PageResponse;
import com.lunchcatch.ops.contract.AuditLogWriter;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.StringJoiner;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자의 포인트 정책 조회와 저장이다.
 *
 * 정책은 적용일이 UNIQUE 인 이력형이고 저장은 항상 내일 적용분이다. 오늘과 내일은 서버 시계(Asia/Seoul)로 정한다.
 * 같은 날 다시 저장하면 내일 행을 덮어쓴다. 지우고 넣지 않고 한 문장 upsert 로 쓴다.
 * 변경 전 값은 잠그지 않고 읽는다. 내일 행이 없을 때 FOR UPDATE 로 읽으면 동시 저장 둘이 간격 잠금을 함께 잡고 교착한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPointPolicyService {

    static final String AUDIT_ACTION = "POINT_POLICY_SAVE";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PointPolicyRepository pointPolicyRepository;
    private final OwnerPointPolicyService ownerPointPolicyService;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public AdminPointPolicyResponse getPolicy() {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        AdminPointPolicyItem current = AdminPointPolicyItem.from(ownerPointPolicyService.currentPolicy());
        AdminPointPolicyItem next = pointPolicyRepository.findByEffectiveDate(tomorrow)
                .map(AdminPointPolicyItem::from)
                .orElse(null);
        return new AdminPointPolicyResponse(current, next);
    }

    // 관리자 표 목록이라 페이지 번호 방식이고 적용일 내림차순으로 고정한다
    public PageResponse<AdminPointPolicyItem> history(int page, int size) {
        if (page < 0) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        int pageSize = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageResponse.from(pointPolicyRepository
                .findAllByOrderByEffectiveDateDesc(PageRequest.of(page, pageSize))
                .map(AdminPointPolicyItem::from));
    }

    /*
     * 내일 적용할 정책을 저장하고 감사 로그를 같은 트랜잭션에 남긴다.
     * 값의 검증은 엔티티 팩터리가 한다. 저장은 엔티티를 거치지 않으므로 검증만을 위해 팩터리를 부른다.
     */
    @Transactional
    public AdminPointPolicyItem save(Long adminId, PointPolicySaveRequest request) {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        PointPolicy after = validated(adminId, request, tomorrow);
        PointPolicy before = pointPolicyRepository.findByEffectiveDate(tomorrow)
                .orElseGet(ownerPointPolicyService::currentPolicy);

        pointPolicyRepository.upsert(tomorrow, after.getMinChargeAmount(), toJson(after.getChargeProducts()),
                after.getChangeReason(), adminId, LocalDateTime.now(clock));
        auditLogWriter.write(adminId, AUDIT_ACTION, tomorrow.toString(),
                "before=" + describe(before) + ";after=" + describe(after));
        return AdminPointPolicyItem.from(after);
    }

    private PointPolicy validated(Long adminId, PointPolicySaveRequest request, LocalDate tomorrow) {
        try {
            return PointPolicy.effectiveFrom(tomorrow, request.minChargeAmount(), request.chargeProducts(),
                    request.reason(), adminId);
        } catch (IllegalArgumentException e) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT, e);
        }
    }

    /*
     * 상품 목록을 JSON 배열 문자열로 만든다. 원소가 모두 숫자라 따옴표가 필요 없다.
     * DB 에서 읽은 목록은 작은 수가 Integer 로 들어올 수 있어 Long 으로 꺼내지 않고 Object 로 다룬다.
     */
    private static String toJson(List<?> products) {
        StringJoiner json = new StringJoiner(",", "[", "]");
        for (Object product : products) {
            json.add(String.valueOf(product));
        }
        return json.toString();
    }

    private static String describe(PointPolicy policy) {
        return "minChargeAmount=" + policy.getMinChargeAmount()
                + ",chargeProducts=" + toJson(policy.getChargeProducts())
                + ",reason=" + policy.getChangeReason();
    }
}
