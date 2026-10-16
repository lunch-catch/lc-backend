package com.launchcatch.store.repository;

import com.launchcatch.global.response.PageCursor;
import com.launchcatch.store.dto.StoreListRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface StoreRepositoryCustom {

    List<StoreListRow> findPage(String query, BigDecimal longitude, BigDecimal latitude,
            List<String> categories, PageCursor cursor, LocalDate businessDate, int limit);
}
