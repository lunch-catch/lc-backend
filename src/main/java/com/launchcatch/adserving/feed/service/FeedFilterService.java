package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.feed.dto.FeedFilterResponse;
import com.launchcatch.adserving.feed.dto.FeedFilterSaveRequest;
import com.launchcatch.adserving.feed.entity.FeedFilter;
import com.launchcatch.adserving.feed.repository.FeedFilterCategoryRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedFilterService {

    private final FeedFilterRepository feedFilterRepository;
    private final FeedFilterCategoryRepository feedFilterCategoryRepository;
    private final Clock clock;

    // 저장한 적이 없으면 전체 업종(빈 배열)과 거리순이다. 피드가 필터 없는 회원을 다루는 기본값과 같다.
    public FeedFilterResponse getFilter(Long memberId) {
        FeedSort sort = feedFilterRepository.findById(memberId).map(FeedFilter::getSortType).orElse(FeedSort.DISTANCE);
        return new FeedFilterResponse(feedFilterCategoryRepository.findCategoryCodes(memberId), sort);
    }

    /*
     * 정렬과 업종을 한 트랜잭션에서 통째로 바꾼다. 업종은 지우고 다시 넣어서 같은 요청을 두 번 보내도 결과가 같다.
     * 같은 코드가 두 번 오면 하나로 합친다(#411 에서 임시로 정했다).
     */
    @Transactional
    public FeedFilterResponse saveFilter(Long memberId, FeedFilterSaveRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        feedFilterRepository.upsertSort(memberId, request.feedSort().name(), now);
        feedFilterCategoryRepository.deleteAllByMemberId(memberId);
        request.categories().stream().distinct()
                .forEach(code -> feedFilterCategoryRepository.insertCategory(memberId, code, now));
        return getFilter(memberId);
    }
}
