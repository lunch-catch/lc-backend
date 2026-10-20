package com.launchcatch.ops.repository;

import com.launchcatch.ops.entity.BatchExecutionLog;
import com.launchcatch.ops.entity.BatchStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/*
 * 배치 실행 기록 저장소다.
 *
 * 갱신은 모두 조건부 UPDATE 로 두고 바뀐 행 수를 돌려준다. 읽고 나서 쓰면 두 서버가 같은 행을
 * 함께 읽고 둘 다 자기 것이라고 믿는 구간이 생긴다. 조건을 WHERE 에 넣으면 그 판정과 쓰기가
 * 한 문장이라 1건을 받은 서버만 이긴다.
 *
 * 점유(upsertClaim)는 조건부 UPDATE 가 아니라 중복만 흡수하는 INSERT 다. 판정은 그 뒤 행을
 * 다시 읽어 owner_id 로 본다(BatchExecutionService.attemptClaim, #526).
 *
 * 갱신 메서드마다 @Transactional 을 붙인다. 부르는 쪽(BatchExecutionService)이 트랜잭션을
 * 걸지 않아서, 없으면 @Modifying 질의가 TransactionRequiredException 으로 터진다. 바깥에
 * 트랜잭션이 있으면 그것에 합류한다.
 */
public interface BatchExecutionLogRepository extends JpaRepository<BatchExecutionLog, Long> {

    Optional<BatchExecutionLog> findByJobNameAndBusinessDate(String jobName, LocalDate businessDate);

    /*
     * assertCurrentAttempt 가 쓴다(#360). 호출자 트랜잭션에 참여해 이 행을 잠근다.
     *
     * 잠그지 않으면 "소유자와 세대를 읽는 것"과 "그 값을 믿고 결과를 공개하는 것" 사이에 다른
     * 서버가 이어받을 틈이 생긴다. 잠근 채로 비교하면 그 틈이 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT l FROM BatchExecutionLog l
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
            """)
    Optional<BatchExecutionLog> findByJobNameAndBusinessDateForUpdate(
            @Param("jobName") String jobName, @Param("businessDate") LocalDate businessDate);

    List<BatchExecutionLog> findByBusinessDate(LocalDate businessDate);

    List<BatchExecutionLog> findByStatusAndOwnerIdNotAndUpdatedAtBefore(
            BatchStatus status, String ownerId, LocalDateTime staleBefore);

    /*
     * 점유를 시도한다. 이미 있는 행은 job_name 을 그대로 두는 값 없는 갱신으로 흡수해, 중복이
     * 정상 결과가 되게 한다. 길이 초과나 CHECK 위반은 그대로 예외로 올라온다(MNT-4-04, #526).
     *
     * 네이티브 쿼리라 JPA auditing 을 타지 않는다. created_at, updated_at 을 Clock 값으로 받는다.
     *
     * 영향받은 행 수는 점유 판정에 쓰지 않는다. 드라이버 기본값(useAffectedRows=false)에서는
     * 무변경 갱신도 1 로 보고될 수 있다. 판정은 이 호출 뒤 그 행의 owner_id 로 한다
     * (BatchExecutionService.attemptClaim).
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO batch_execution_log (job_name, business_date, status, owner_id, created_at, updated_at)
            VALUES (:jobName, :businessDate, 'RUNNING', :ownerId, :now, :now)
            ON DUPLICATE KEY UPDATE job_name = job_name
            """, nativeQuery = true)
    void upsertClaim(@Param("jobName") String jobName,
                     @Param("businessDate") LocalDate businessDate,
                     @Param("ownerId") String ownerId,
                     @Param("now") LocalDateTime now);

    /*
     * 생존 신호를 갱신한다. 내 소유이고 실행 중일 때만 걸린다.
     * attemptNo 를 주면 그 세대일 때만 걸린다(#591). null 이면 세대를 보지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.updatedAt = :now
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING
               AND l.ownerId = :ownerId
               AND (:attemptNo IS NULL OR l.attemptNo = :attemptNo)
            """)
    int renew(@Param("jobName") String jobName,
              @Param("businessDate") LocalDate businessDate,
              @Param("ownerId") String ownerId,
              @Param("attemptNo") Integer attemptNo,
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
     *
     * attemptNo 를 하나 올린다(#360). 이어받기도 소유자가 바뀌는 자리라 세대가 오른다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.ownerId = :ownerId, l.updatedAt = :now, l.attemptNo = l.attemptNo + 1
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
     * 처리 건수도 이 문장에서 함께 정한다(#359). null 을 받으면 null 로 덮어써서, 이전 시도가
     * 남긴 건수가 이번 시도에 그대로 남는 것을 막는다. succeed(건수 없음)와 fail 은 null 을
     * 넘긴다.
     *
     * 소유자도 조건이다. 생존 신호가 끊겼다가 늦게 깨어난 서버가 남이 들고 있는 실행을 닫는
     * 것을 막는다.
     *
     * attemptNo 를 주면 세대도 조건이다(#591). 같은 서버가 새 세대를 잡았으면 소유자 조건은
     * 통과하므로, 낡은 시도가 새 시도의 행을 닫는 것은 세대로만 막힌다. null 이면 세대를 보지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.status = :status, l.finishedAt = :now, l.failureReason = :reason,
                   l.processedCount = :processedCount, l.updatedAt = :now
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING
               AND l.ownerId = :ownerId
               AND (:attemptNo IS NULL OR l.attemptNo = :attemptNo)
            """)
    int close(@Param("jobName") String jobName,
              @Param("businessDate") LocalDate businessDate,
              @Param("status") BatchStatus status,
              @Param("reason") String reason,
              @Param("processedCount") Integer processedCount,
              @Param("ownerId") String ownerId,
              @Param("attemptNo") Integer attemptNo,
              @Param("now") LocalDateTime now);

    /*
     * 자동 재실행 한 번을 기록한다. 상한에 닿았으면 0건이다.
     *
     * 상한을 WHERE 에 넣는다. 코드에서만 세면 두 서버가 겹쳐 돌 때 셋 이상이 될 수 있고,
     * 그때는 chk_batch_retry 위반으로 저장이 거부되어 재실행 기록 자체가 사라진다.
     * 0건을 "더 돌리지 않는다" 로 읽으면 판정과 기록이 한 문장에 있다.
     *
     * 소유자도 조건이다. 없으면 임대를 잃은 서버가 새 소유자의 재실행 예산을 깎고 사유를
     * 덮어쓴다. 재실행 횟수는 그 행을 들고 있는 서버만 쓸 수 있다.
     *
     * attemptNo 를 주면 세대도 조건이다(#591). null 이면 세대를 보지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.retryCount = l.retryCount + 1, l.retryReason = :reason, l.updatedAt = :now
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING
               AND l.ownerId = :ownerId
               AND (:attemptNo IS NULL OR l.attemptNo = :attemptNo)
               AND l.retryCount < :maxRetries
            """)
    int recordRetry(@Param("jobName") String jobName,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("reason") String reason,
                    @Param("ownerId") String ownerId,
                    @Param("attemptNo") Integer attemptNo,
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
     *
     * 시작 시각(createdAt)과 처리 건수도 새로 한다(#359). 남겨 두면 운영 대시보드(A-06)가
     * 지난 시도의 시작 시각으로 소요 시간을 계산하고, 지난 시도의 건수를 이번 시도의 것으로
     * 보여 준다.
     *
     * attemptNo 를 하나 올린다(#360). 수동 재실행도 새 시도이고, 이전 시도가 늦게 깨어나
     * 결과를 공개하려 하면 세대가 달라 assertCurrentAttempt 에서 막힌다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING,
                   l.finishedAt = null, l.failureReason = null,
                   l.retryCount = 0, l.retryReason = null, l.processedCount = null,
                   l.createdAt = :now,
                   l.ownerId = :ownerId, l.updatedAt = :now, l.attemptNo = l.attemptNo + 1
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status = com.launchcatch.ops.entity.BatchStatus.FAILED
            """)
    int restartFailed(@Param("jobName") String jobName,
                      @Param("businessDate") LocalDate businessDate,
                      @Param("ownerId") String ownerId,
                      @Param("now") LocalDateTime now);

    /*
     * 끝난 행(FAILED, SUCCESS)을 실행 중으로 되돌린다. 분석이 쓰는 재실행이다.
     *
     * restartFailed와 리셋하는 필드는 같다(시작 시각과 처리 건수 포함, #359). RUNNING이면
     * 조건에 걸리지 않아 0건이고, 두 번 눌러도 한 번만 걸린다. restartFailed는 "실패 재시도"
     * 의미를 그대로 두려고 건드리지 않는다. attemptNo 도 restartFailed와 같은 이유로
     * 하나 올린다(#360).
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE BatchExecutionLog l
               SET l.status = com.launchcatch.ops.entity.BatchStatus.RUNNING,
                   l.finishedAt = null, l.failureReason = null,
                   l.retryCount = 0, l.retryReason = null, l.processedCount = null,
                   l.createdAt = :now,
                   l.ownerId = :ownerId, l.updatedAt = :now, l.attemptNo = l.attemptNo + 1
             WHERE l.jobName = :jobName
               AND l.businessDate = :businessDate
               AND l.status IN (com.launchcatch.ops.entity.BatchStatus.FAILED,
                                 com.launchcatch.ops.entity.BatchStatus.SUCCESS)
            """)
    int restartFinished(@Param("jobName") String jobName,
                        @Param("businessDate") LocalDate businessDate,
                        @Param("ownerId") String ownerId,
                        @Param("now") LocalDateTime now);
}
