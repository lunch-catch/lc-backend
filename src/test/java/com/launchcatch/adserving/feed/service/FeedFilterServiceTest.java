package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.feed.dto.FeedFilterResponse;
import com.launchcatch.adserving.feed.entity.FeedFilter;
import com.launchcatch.adserving.feed.repository.FeedFilterCategoryRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("피드 필터 서비스")
class FeedFilterServiceTest {

    private static final Long MEMBER_ID = 3001L;

    private final FeedFilterRepository feedFilterRepository = mock(FeedFilterRepository.class);
    private final FeedFilterCategoryRepository feedFilterCategoryRepository = mock(FeedFilterCategoryRepository.class);
    private final FeedFilterService service = new FeedFilterService(feedFilterRepository, feedFilterCategoryRepository);

    // 필터는 저장 API 가 만들고 엔티티에 생성 경로가 아직 없어 리플렉션으로 채운다
    private static FeedFilter filter(FeedSort sort) {
        FeedFilter filter = BeanUtils.instantiateClass(FeedFilter.class);
        ReflectionTestUtils.setField(filter, "memberId", MEMBER_ID);
        ReflectionTestUtils.setField(filter, "sortType", sort);
        return filter;
    }

    @Test
    void 저장한_정렬과_업종을_돌려준다() {
        when(feedFilterRepository.findById(MEMBER_ID)).thenReturn(Optional.of(filter(FeedSort.DISCOUNT_RATE)));
        when(feedFilterCategoryRepository.findCategoryCodes(MEMBER_ID)).thenReturn(List.of("JAPANESE", "KOREAN"));

        FeedFilterResponse response = service.getFilter(MEMBER_ID);

        assertThat(response).isEqualTo(new FeedFilterResponse(List.of("JAPANESE", "KOREAN"), FeedSort.DISCOUNT_RATE));
    }

    @Test
    void 저장한_적이_없으면_전체_업종과_거리순이다() {
        when(feedFilterRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());
        when(feedFilterCategoryRepository.findCategoryCodes(MEMBER_ID)).thenReturn(List.of());

        FeedFilterResponse response = service.getFilter(MEMBER_ID);

        assertThat(response).isEqualTo(new FeedFilterResponse(List.of(), FeedSort.DISTANCE));
    }

    @Test
    void 정렬만_저장하고_업종을_고르지_않았으면_빈_배열이다() {
        when(feedFilterRepository.findById(MEMBER_ID)).thenReturn(Optional.of(filter(FeedSort.DISTANCE)));
        when(feedFilterCategoryRepository.findCategoryCodes(MEMBER_ID)).thenReturn(List.of());

        assertThat(service.getFilter(MEMBER_ID).categories()).isEmpty();
    }
}
