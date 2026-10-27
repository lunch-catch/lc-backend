package com.lunchcatch.adserving.feed.repository;

import com.lunchcatch.adserving.feed.entity.SwipeLog;

public interface SwipeLogInsert {

    // 같은 (serveId, 사용자)가 이미 있으면 넣지 않는다. 처음 넣었으면 true 다.
    boolean insertIgnore(SwipeLog log);
}
