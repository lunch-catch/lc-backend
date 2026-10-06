package com.launchcatch.ops.entity;

import com.launchcatch.global.entity.BaseTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 배치 작업 한 번의 실행 기록이다.
 *
 * (작업명, 영업일) 이 유일하다. 두 배치 서버가 같은 시각에 같은 작업을 시도하면 먼저 넣은
 * 쪽만 성공하고 나머지는 유일 제약에 걸린다. 그것이 중복 실행을 막는 방법이다.
 *
 * updated_at 은 수정 시각이 아니라 생존 신호다. 실행 중인 서버가 30초마다 갱신하고,
 * 2분 넘게 멈춰 있으면 다른 서버가 이어받는다 (배치 운영 문서 2장).
 */
@Entity
@Table(name = "batch_execution_log")
@AttributeOverride(name = "id", column = @Column(name = "batch_execution_log_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatchExecutionLog extends BaseTimeEntity {

    @Column(name = "job_name", nullable = false, length = 50, updatable = false)
    private String jobName;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "processed_count")
    private Integer processedCount;

    @Column(name = "owner_id", nullable = false, length = 100)
    private String ownerId;

    private BatchExecutionLog(String jobName, LocalDate businessDate, String ownerId) {
        this.jobName = jobName;
        this.businessDate = businessDate;
        this.ownerId = ownerId;
        this.status = BatchStatus.RUNNING;
    }

    /** 점유를 시도하는 행이다. 상태는 항상 RUNNING 으로 시작한다. */
    public static BatchExecutionLog running(String jobName, LocalDate businessDate, String ownerId) {
        return new BatchExecutionLog(jobName, businessDate, ownerId);
    }
}
