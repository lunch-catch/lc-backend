package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.PointPolicy;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * 포인트 정책 저장소다.
 * 이력형 테이블이라 지금 적용 중인 정책은 적용일이 오늘 이하인 행 중 가장 늦은 행이고, effective_date UNIQUE 인덱스를 탄다.
 */
public interface PointPolicyRepository extends JpaRepository<PointPolicy, Long> {

    // 적용일이 그 날짜 이하인 정책 중 가장 최근 것을 찾는다
    Optional<PointPolicy> findFirstByEffectiveDateLessThanEqualOrderByEffectiveDateDesc(LocalDate date);
}
