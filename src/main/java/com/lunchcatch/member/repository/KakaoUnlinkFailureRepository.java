package com.lunchcatch.member.repository;

import com.lunchcatch.member.entity.KakaoUnlinkFailure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

public interface KakaoUnlinkFailureRepository extends JpaRepository<KakaoUnlinkFailure, Long> {

    Optional<KakaoUnlinkFailure> findByMember_Id(Long memberId);

    boolean existsByProviderUserId(String providerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT failure FROM KakaoUnlinkFailure failure WHERE failure.id = :id")
    Optional<KakaoUnlinkFailure> findByIdForUpdate(@Param("id") Long id);

    /*
     * 아직 해제되지 않은 행을 오래된 것부터 읽는다.
     *
     * 배치 사이의 중복은 batch_execution_log 의 작업 점유가 막는다.
     * 최초 호출과 배치의 중복은 호출 직전 행 잠금과 processing 선점이 막는다.
     *
     * resolved 와 processing 등호 조건 뒤에 생성 시각과 PK 를 붙여 정렬까지 인덱스로 읽는다.
     * PK 는 생성 시각이 같은 행들의 순서를 고정한다.
     *
     * 한 번에 다 읽지 않는 것은 외부 호출이 건마다 붙기 때문이다. 남은 행은 다음 날이 집는다.
     */
    @Query("""
            SELECT failure
              FROM KakaoUnlinkFailure failure
             WHERE failure.resolved = FALSE AND failure.processing = FALSE
             ORDER BY failure.createdAt ASC, failure.id ASC
            """)
    List<KakaoUnlinkFailure> findPendingOldestFirst(Pageable pageable);

    void deleteByMember_Id(Long memberId);
}
