package com.launchcatch.adserving.impression.repository;

import com.launchcatch.adserving.impression.entity.ImpressionLog;

public interface ImpressionLogInsert {

    // 같은 (serveId, 사용자)가 이미 있으면 넣지 않는다. 처음 넣었으면 true 다.
    boolean insertIgnore(ImpressionLog log);
}
