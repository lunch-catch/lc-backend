package com.launchcatch.billing.service;

import com.launchcatch.billing.dto.PointAdjustmentRequest;
import com.launchcatch.billing.dto.PointAdjustmentResult;
import com.launchcatch.billing.exception.BillingErrorCode;
import com.launchcatch.billing.exception.BillingException;
import com.launchcatch.global.exception.CommonErrorCode;
import com.launchcatch.owner.contract.OwnerStatusQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/*
 * 관리자의 포인트 수동 조정이다. 사후에 확인된 과오 차감을 양수로 돌려주는 것이 주 용도다.
 *
 * 이 클래스는 트랜잭션 밖에서 입력을 검사한다. 원장 기록은 PointAdjustmentRecorder 가 한 트랜잭션에서 한다.
 * 점주 확인을 트랜잭션 밖에 두는 이유는 점주 계정 행의 잠금을 다른 도메인 조회 동안 잡고 있지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class PointAdjustmentService {

    private final OwnerStatusQuery ownerStatusQuery;
    private final PointAdjustmentRecorder recorder;

    public PointAdjustmentResult adjust(Long adminId, PointAdjustmentRequest request) {
        // 대사 건을 닫는 조정은 과오 차감 정정이라 양수만 받는다
        if (request.amount() == 0 || (request.mismatchId() != null && request.amount() < 0)) {
            throw new BillingException(CommonErrorCode.INVALID_INPUT);
        }
        /*
         * 잘못 입력한 ID 로 아무에게도 속하지 않는 원장 줄이 생기지 않게 막는 입력 검사다.
         * 상태값은 쓰지 않는다. 정지, 탈퇴한 점주도 그 전에 생긴 과오 차감은 정정할 수 있어야 한다.
         */
        if (ownerStatusQuery.findStatus(request.ownerId()).isEmpty()) {
            throw new BillingException(BillingErrorCode.OWNER_NOT_FOUND);
        }
        return recorder.record(adminId, request);
    }
}
