package com.launchcatch.ops.scheduler;

import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/*
 * 알림 채널이 생기기 전까지 쓰는 구현이다.
 *
 * 아무것도 하지 않는 구현을 두지 않는다. 그러면 이어받기와 실패가 흔적 없이 지나가고, 나중에
 * 채널을 붙일 때까지 그 구간이 빈 것을 알 수 없다. 로그로라도 남기면 운영 대시보드의 WARN
 * 수치에 걸린다.
 */
@Slf4j
@Component
public class LoggingBatchAlert implements BatchAlert {

    @Override
    public void takenOver(String jobName, LocalDate businessDate, String previousOwner, String newOwner) {
        log.warn("event=BATCH_JOB_TAKEN_OVER job={} businessDate={} previousOwner={} newOwner={}",
                jobName, businessDate, previousOwner, newOwner);
    }

    @Override
    public void failed(String jobName, LocalDate businessDate, String reason) {
        log.error("event=BATCH_JOB_FAILED job={} businessDate={} reason={}",
                jobName, businessDate, reason);
    }
}
