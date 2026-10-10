package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.PointPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

// 포인트 정책 저장소이며 적용일 기준 조회는 그것을 쓰는 작업과 함께 더한다
public interface PointPolicyRepository extends JpaRepository<PointPolicy, Long> {
}
