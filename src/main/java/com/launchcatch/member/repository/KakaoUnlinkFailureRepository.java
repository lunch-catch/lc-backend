package com.launchcatch.member.repository;

import com.launchcatch.member.entity.KakaoUnlinkFailure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KakaoUnlinkFailureRepository extends JpaRepository<KakaoUnlinkFailure, Long> {

    Optional<KakaoUnlinkFailure> findByMember_Id(Long memberId);

    /*
     * 아직 해제되지 않은 행을 오래된 것부터 읽는다.
     *
     * 행 단위 선점이나 FOR UPDATE SKIP LOCKED 를 쓰지 않는다. 두 배치 서버가 겹치는 것은
     * batch_execution_log 의 작업 점유가 막는다(배치 운영 문서 2장). 여기까지 온 서버는
     * 그 작업을 혼자 들고 있다.
     *
     * 조회 조건이 resolved 하나라 등호 뒤에 바로 정렬이 온다. 범위 조건을 섞으면 정렬이
     * 인덱스를 못 타고 filesort 로 떨어진다. PK 를 마지막에 두는 것은 created_at 이 같은
     * 행들의 순서를 고정해 페이지가 겹치거나 빠지지 않게 하려는 것이다.
     *
     * 한 번에 다 읽지 않는 것은 외부 호출이 건마다 붙기 때문이다. 남은 행은 다음 날이 집는다.
     */
    @Query("""
            SELECT failure
              FROM KakaoUnlinkFailure failure
             WHERE failure.resolved = FALSE
             ORDER BY failure.createdAt ASC, failure.id ASC
            """)
    List<KakaoUnlinkFailure> findPendingOldestFirst(Pageable pageable);

    void deleteByMember_Id(Long memberId);
}
