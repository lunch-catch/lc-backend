package com.launchcatch.adserving.impression.repository;

import com.launchcatch.adserving.impression.entity.ImpressionLog;
import com.launchcatch.adserving.impression.entity.ImpressionLogId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface ImpressionLogInsert {

    /*
     * 묶음을 넣고 그 가운데 새로 들어간 행의 키를 돌려준다. 같은 (serveId, 사용자)가 이미 있으면 넣지 않는다.
     * 호출하는 쪽의 트랜잭션 안에서 부른다. 새로 들어간 행을 가리는 읽기가 넣은 행을 봐야 한다.
     */
    Set<ImpressionLogId> insertAllIgnore(List<Row> rows);

    // receivedAt 은 노출을 받은 한국 시각이다. created_at 에 남고 영업일과 같은 시각에서 나와야 한다(#783).
    record Row(ImpressionLog log, LocalDateTime receivedAt) {
    }
}
