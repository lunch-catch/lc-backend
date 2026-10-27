package com.lunchcatch.billing.service;

import com.lunchcatch.billing.dto.PointPolicyResponse;
import com.lunchcatch.billing.entity.PointPolicy;
import com.lunchcatch.billing.repository.PointPolicyRepository;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 오늘 적용 중인 충전 정책을 읽는다. 충전 화면과 주문 생성이 같은 기준을 쓴다.
 *
 * 오늘은 서버 시계(Asia/Seoul)로 정한다. 관리자가 저장한 정책은 다음 날 00:00 부터 적용되므로,
 * 자정 직후에는 전날 저장한 정책이 바로 보여야 한다. DB 날짜 함수로 오늘을 정하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerPointPolicyService {

    private final PointPolicyRepository pointPolicyRepository;
    private final Clock clock;

    public PointPolicyResponse getCurrent() {
        return PointPolicyResponse.from(currentPolicy());
    }

    /*
     * 오늘 적용 중인 정책 엔티티다.
     * 최초 정책 행을 V3 가 넣으므로 없을 수 없다. 없으면 배포나 데이터 문제라 숨기지 않고 서버 오류로 드러낸다.
     */
    public PointPolicy currentPolicy() {
        LocalDate today = LocalDate.now(clock);
        return pointPolicyRepository.findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(today)
                .orElseThrow(() -> new IllegalStateException("적용 중인 포인트 정책이 없습니다: " + today));
    }
}
