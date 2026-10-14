package com.launchcatch.billing.repository;

import com.launchcatch.billing.entity.PointAccount;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
 * 점주 포인트 계정 저장소다.
 * 잠금 조회(비관적 잠금)는 그 잠금을 처음 쓰는 작업과 함께 더한다. 잠금 메서드를 쓰는 자리
 * 없이 미리 두면 대기 시간 상한과 실패 처리를 함께 정할 수 없다.
 */
public interface PointAccountRepository extends JpaRepository<PointAccount, Long> {

    /*
     * 점주의 계정 행이 없으면 만들고 있으면 그대로 둔다. 첫 충전이 부른다.
     *
     * 중복은 값을 바꾸지 않는 갱신(ON DUPLICATE KEY UPDATE owner_id = owner_id)으로 흡수한다. 예외가 나지 않으므로
     * 부른 트랜잭션이 롤백 표시가 되지 않는다. INSERT IGNORE 는 쓰지 않는다. 중복뿐 아니라 CHECK 와 NOT NULL
     * 위반까지 경고로 낮추고 행을 조용히 버린다(구현 계획 D-5).
     *
     * 영향받은 행 수는 새로 만들면 1, 이미 있으면 0 이다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO point_account (owner_id, created_at, updated_at)
            VALUES (:ownerId, :now, :now)
            ON DUPLICATE KEY UPDATE owner_id = owner_id
            """, nativeQuery = true)
    int createIfAbsent(@Param("ownerId") Long ownerId, @Param("now") LocalDateTime now);
}
