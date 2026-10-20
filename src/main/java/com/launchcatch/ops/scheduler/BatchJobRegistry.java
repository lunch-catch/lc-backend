package com.launchcatch.ops.scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/*
 * 배치 작업 이름을 묶음, 단계, 묶음 밖 작업, 알 수 없음으로 가른다(#585).
 *
 * 이어받기(DailyJobScheduler.takeOverStale), 관리자 수동 재실행과 이력의 retryable 이 모두 이 판정을 쓴다.
 * 판정이 흩어지면 같은 이름을 서로 다르게 분류하는 자리가 생긴다.
 */
@Component
public class BatchJobRegistry {

    /** 묶음 전체의 점유를 나타내는 작업 이름이다. 단계는 각자 자기 이름으로 행을 남긴다. */
    public static final String BUNDLE = "DAILY_0000";

    public enum Kind {
        BUNDLE(true),
        STEP(false),
        STANDALONE(true),
        UNKNOWN(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        /** 다시 실행할 수 있는지 본다. 단계는 묶음 드라이버로만 다시 돌고 알 수 없는 이름은 다시 돌릴 길이 없다. */
        public boolean isRetryable() {
            return retryable;
        }
    }

    private final Set<String> stepNames;
    private final Map<String, StandaloneJob> standaloneJobsByName;

    /*
     * 겹치는 이름은 기동 시점에 막는다.
     * 단계와 묶음 밖 작업이 겹치면 단계로 먼저 분류되어 그 묶음 밖 작업은 이어받아도 조용히 다시 돌지 않는다.
     */
    public BatchJobRegistry(List<DailyJob> steps, List<StandaloneJob> standaloneJobs) {
        this.stepNames = steps.stream().map(DailyJob::jobName).collect(Collectors.toSet());
        this.standaloneJobsByName = new HashMap<>();
        for (StandaloneJob job : standaloneJobs) {
            if (stepNames.contains(job.jobName())) {
                throw new IllegalStateException("묶음 밖 작업 이름이 단계 이름과 겹친다: " + job.jobName());
            }
            if (standaloneJobsByName.putIfAbsent(job.jobName(), job) != null) {
                throw new IllegalStateException("묶음 밖 작업 이름이 겹친다: " + job.jobName());
            }
        }
    }

    public Kind classify(String jobName) {
        if (BUNDLE.equals(jobName)) {
            return Kind.BUNDLE;
        }
        if (stepNames.contains(jobName)) {
            return Kind.STEP;
        }
        if (standaloneJobsByName.containsKey(jobName)) {
            return Kind.STANDALONE;
        }
        return Kind.UNKNOWN;
    }

    // 등록되지 않은 이름이면 던진다. 호출자가 먼저 걸렀다는 가정을 주석에만 두지 않는다.
    public StandaloneJob standaloneJob(String jobName) {
        StandaloneJob job = standaloneJobsByName.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("등록된 묶음 밖 작업이 아니다: " + jobName);
        }
        return job;
    }
}
