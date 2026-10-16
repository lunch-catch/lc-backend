package com.launchcatch.adserving.feed.service;

import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.feed.dto.FeedFilterResponse;
import com.launchcatch.adserving.feed.entity.FeedFilter;
import com.launchcatch.adserving.feed.repository.FeedFilterCategoryRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FeedFilterService {

    private final FeedFilterRepository feedFilterRepository;
    private final FeedFilterCategoryRepository feedFilterCategoryRepository;

    // 저장한 적이 없으면 전체 업종(빈 배열)과 거리순이다. 피드가 필터 없는 회원을 다루는 기본값과 같다.
    public FeedFilterResponse getFilter(Long memberId) {
        FeedSort sort = feedFilterRepository.findById(memberId).map(FeedFilter::getSortType).orElse(FeedSort.DISTANCE);
        return new FeedFilterResponse(feedFilterCategoryRepository.findCategoryCodes(memberId), sort);
    }
}
