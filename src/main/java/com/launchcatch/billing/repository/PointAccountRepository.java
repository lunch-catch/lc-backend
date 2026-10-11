package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.PointAccount;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * 점주 포인트 계정 저장소다.
 * 잠금 조회(비관적 잠금)는 그 잠금을 처음 쓰는 작업과 함께 더한다. 잠금 메서드를 쓰는 자리
 * 없이 미리 두면 대기 시간 상한과 실패 처리를 함께 정할 수 없다.
 */
public interface PointAccountRepository extends JpaRepository<PointAccount, Long> {
}
