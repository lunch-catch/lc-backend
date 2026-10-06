package com.launchcatch.ops.repository;

import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/*
 * 배치 실행 기록 저장소다.
 *
 * 갱신은 모두 조건부 UPDATE 로 두고 바뀐 행 수를 돌려준다. 읽고 나서 쓰면 두 서버가 같은 행을
 * 함께 읽고 둘 다 자기 것이라고 믿는 구간이 생긴다. 조건을 WHERE 에 넣으면 그 판정과 쓰기가
 * 한 문장이라 1건을 받은 서버만 이긴다.
 *
 * 갱신 메서드마다 @Transactional 을 붙인다. 점유 판정이 유일 제약 위반을 쓰는 탓에 부르는 쪽
 * (BatchExecutionService.begin)이 트랜잭션을 걸 수 없고, 그러면 @Modifying 질의가
 * TransactionRequiredException 으로 터진다. 바깥에 트랜잭션이 있으면 그것에 합류한다.
 */
public interface BatchExecutionLogRepository extends JpaRepository<BatchExecutionLog, Long> {

    Optional<BatchExecutionLog> findByJobNameAndBusinessDate(String jobName, LocalDate businessDate);

    List<BatchExecutionLog> findByStatusAndOwnerIdNotAndUpdatedAtBefore(
            BatchStatus status, String ownerId, LocalDateTime staleBefore);

    /*
     * 점유는 이 메서드의 유일 제약 위반으로 판정한다. 그래서 자기 트랜잭션에서 돈다.
     *
     * 제약 위반이 나면 Hibernate 가 그 트랜잭션을 되돌릴 것으로 표시한다. 바깥 트랜잭션 안에서
     * 터지면 예외를 잡아 "이미 점유됐다" 로 넘겨도 커밋 시점에 되돌아간다. 트랜잭션을 떼어 두면
     * 되돌아가는 것이 이 삽입 하나뿐이고, 부르는 쪽은 예외를 판정에 쓸 수 있다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    <S extends BatchExecutionLog> S saveAndFlush(S entity);

    /** 생존 신호를 갱신한다. 내 소유이고 실행 중일 때만 걸린다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.updatedAt = :now
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING
               AND l.ownerId = :ownerId
            """)
    int renew(@Param("jobName") String jobName,
              @Param("businessDate") LocalDate businessDate,
              @Param("ownerId") String ownerId,
              @Param("now") LocalDateTime now);

    /** 내가 들고 있는 실행 중 행을 한 번에 갱신한다. 별도 스레드의 생존 신호가 쓴다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.updatedAt = :now
             WHERE l.ownerId = :ownerId
               AND l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING
            """)
    int renewOwned(@Param("ownerId") String ownerId, @Param("now") LocalDateTime now);

    /*
     * 멈춘 행의 소유자를 나로 바꾼다.
     * updatedAt 조건을 함께 넣어, 그 사이 원래 서버가 살아나 갱신했으면 걸리지 않게 한다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.ownerId = :ownerId, l.updatedAt = :now
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING
               AND l.updatedAt < :staleBefore
            """)
    int takeOver(@Param("jobName") String jobName,
                 @Param("businessDate") LocalDate businessDate,
                 @Param("ownerId") String ownerId,
                 @Param("now") LocalDateTime now,
                 @Param("staleBefore") LocalDateTime staleBefore);

    /*
     * 실행 중 행을 닫는다. 성공이면 사유가 없고 실패면 사유가 있다.
     * chk_batch_finished 가 "RUNNING 이 아니면 finished_at 이 있어야 한다" 를 요구하므로
     * 상태와 종료 시각을 같은 문장에서 함께 넣는다.
     *
     * 소유자도 조건이다. 생존 신호가 끊겼다가 늦게 깨어난 서버가 남이 들고 있는 실행을 닫는
     * 것을 막는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.status = :status, l.finishedAt = :now, l.failureReason = :reason,
                   l.updatedAt = :now
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING
               AND l.ownerId = :ownerId
            """)
    int close(@Param("jobName") String jobName,
              @Param("businessDate") LocalDate businessDate,
              @Param("status") BatchStatus status,
              @Param("reason") String reason,
              @Param("ownerId") String ownerId,
              @Param("now") LocalDateTime now);

    /*
     * 자동 재실행 한 번을 기록한다. 상한에 닿았으면 0건이다.
     *
     * 상한을 WHERE 에 넣는다. 코드에서만 세면 두 서버가 겹쳐 돌 때 셋 이상이 될 수 있고,
     * 그때는 chk_batch_retry 위반으로 저장이 거부되어 재실행 기록 자체가 사라진다.
     * 0건을 "더 돌리지 않는다" 로 읽으면 판정과 기록이 한 문장에 있다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.retryCount = l.retryCount + 1, l.retryReason = :reason, l.updatedAt = :now
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING
               AND l.retryCount < :maxRetries
            """)
    int recordRetry(@Param("jobName") String jobName,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("reason") String reason,
                    @Param("maxRetries") int maxRetries,
                    @Param("now") LocalDateTime now);

    /*
     * 실패한 행을 실행 중으로 되돌린다. 관리자의 수동 재실행이다.
     *
     * 상태가 FAILED 일 때만 걸리게 해서 두 번 눌러도 한 번만 실행된다.
     * chk_batch_failure_reason 과 chk_batch_finished 가 짝을 요구하므로 둘을 함께 비운다.
     *
     * 자동 재실행 횟수도 0 으로 되돌린다. 남겨 두면 다음 자동 재실행이 시작부터 상한이라
     * 한 번도 돌지 않고 바로 FAILED 가 된다. chk_batch_retry 가 횟수와 사유를 짝으로
     * 요구하므로 둘을 함께 비운다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING,
                   l.finishedAt = null, l.failureReason = null,
                   l.retryCount = 0, l.retryReason = null,
                   l.ownerId = :ownerId, l.updatedAt = :now
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.FAILED
            """)
    int restartFailed(@Param("jobName") String jobName,
                      @Param("businessDate") LocalDate businessDate,
                      @Param("ownerId") String ownerId,
                      @Param("now") LocalDateTime now);
}
