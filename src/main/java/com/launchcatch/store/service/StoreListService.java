package com.launchcatch.store.service;

import com.launchcatch.global.response.CursorPageResponse;
import com.launchcatch.global.response.PageCursor;
import com.launchcatch.global.response.PageTokens;
import com.launchcatch.store.StoreImageUrlResolver;
import com.launchcatch.store.dto.StoreCategoryFilter;
import com.launchcatch.store.dto.StoreListItemResponse;
import com.launchcatch.store.dto.StoreListRequest;
import com.launchcatch.store.dto.StoreListRow;
import com.launchcatch.store.repository.StoreRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
public class StoreListService {
    private final StoreRepository storeRepository;
    private final StoreImageUrlResolver images;
    private final Clock clock;

    public StoreListService(StoreRepository storeRepository, @Value("${cdn.base-url:}") String baseUrl, Clock clock) {
        this.storeRepository = storeRepository;
        this.images = new StoreImageUrlResolver(baseUrl);
        this.clock = clock;
    }

    public CursorPageResponse<StoreListItemResponse> find(StoreListRequest request) {
        int pageSize = request.pageSize() == null ? 20 : request.pageSize();
        List<String> categories = request.filter().contains(StoreCategoryFilter.ALL) ? List.of()
                : request.filter().stream().distinct().map(Enum::name).toList();
        PageCursor cursor = decodeCursor(request.pageToken());
        // 다음 페이지 유무를 확인하려고 요청한 개수보다 한 행 더 가져온다.
        List<StoreListRow> rows = storeRepository.findPage(request.query(), request.longitude(), request.latitude(),
                categories, cursor, LocalDate.now(clock), pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        // 추가로 읽은 행은 응답에서 제외하고 실제 반환한 마지막 가게로 다음 커서를 만든다.
        List<StoreListRow> page = hasNext ? rows.subList(0, pageSize) : rows;
        List<StoreListItemResponse> items = page.stream().map(this::item).toList();
        String nextPageToken = hasNext ? createPageToken(page.getLast()) : null;
        return CursorPageResponse.of(items, nextPageToken);
    }

    private StoreListItemResponse item(StoreListRow row) {
        String key = row.storeImageObjectKey();
        if (key != null && key.isBlank()) {
            log.warn("event=STORE_LIST_IMAGE_SKIPPED storeId={} reason=EMPTY_OBJECT_KEY fallback=OMIT_IMAGE",
                    row.storeId());
            key = null;
        }
        var item = StoreListItemResponse.from(row, images.resolve(key));
        if (row.campaignId() != null && !item.hasActiveCampaign()) {
            log.warn("event=STORE_LIST_CAMPAIGN_SKIPPED storeId={} campaignId={} reason=TARGET_MENU_MISSING fallback=OMIT_CAMPAIGN",
                    row.storeId(), row.campaignId());
        }
        return item;
    }

    private static String createPageToken(StoreListRow last) {
        // 소수 거리를 문자열로 보존하여 다음 요청에서도 같은 거리 경계값을 복원한다.
        return PageTokens.encode(new PageCursor(last.storeId(), Double.toString(last.distanceMeters())));
    }

    private static PageCursor decodeCursor(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        // 공통 디코더는 잘못된 토큰도 null로 반환하므로 전달된 토큰의 형식 오류를 여기서 거부한다.
        PageCursor cursor = PageTokens.decode(token);
        if (cursor == null || cursor.id() <= 0 || cursor.sortValue() == null) {
            throw new IllegalArgumentException("페이지 커서가 올바르지 않습니다.");
        }
        double distance = Double.parseDouble(cursor.sortValue());
        if (!Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("페이지 커서의 거리가 올바르지 않습니다.");
        }
        return cursor;
    }
}
