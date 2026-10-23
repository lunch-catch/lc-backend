package com.launchcatch.adserving.impression.repository;

import com.launchcatch.adserving.impression.entity.ImpressionLog;
import java.time.LocalDateTime;

public interface ImpressionLogInsert {

    /*
     * 같은 (serveId, 사용자)가 이미 있으면 넣지 않는다. 처음 넣었으면 true 다.
     * receivedAt 은 노출을 받은 한국 시각이다. created_at 에 남고 영업일과 같은 시각에서 나와야 한다(#783).
     */
    boolean insertIgnore(ImpressionLog log, LocalDateTime receivedAt);
}
