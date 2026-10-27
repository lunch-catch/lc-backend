package com.launchcatch.adserving.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.adserving.engine.FeedSort;
import com.launchcatch.adserving.feed.dto.FeedFilterResponse;
import com.launchcatch.adserving.feed.dto.FeedFilterSaveRequest;
import com.launchcatch.adserving.feed.entity.FeedFilter;
import com.launchcatch.adserving.feed.repository.FeedFilterCategoryRepository;
import com.launchcatch.adserving.feed.repository.FeedFilterRepository;
import com.launchcatch.adserving.feed.repository.FeedStateRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("피드 필터 서비스")
class FeedFilterServiceTest {

    private static final Long MEMBER_ID = 3001L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-10-16T05:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZONE);

    private final FeedFilterRepository feedFilterRepository = mock(FeedFilterRepository.class);
    private final FeedFilterCategoryRepository feedFilterCategoryRepository = mock(FeedFilterCategoryRepository.class);
    private final FeedStateRepository feedStateRepository = mock(FeedStateRepository.class);
    private final FeedFilterService service = new FeedFilterService(
            feedFilterRepository, feedFilterCategoryRepository, feedStateRepository, Clock.fixed(NOW, ZONE));

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

    // 커밋 전에 지우면 그사이 피드가 바뀌기 전 값을 다시 캐시에 둔다(#1042 결정 4)
    @Test
    void 저장하면_커밋된_뒤에_필터_캐시를_지운다() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.saveFilter(MEMBER_ID, new FeedFilterSaveRequest(List.of("KOREAN"), "DISTANCE"));

            verify(feedStateRepository, never()).evictFilter(MEMBER_ID);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(feedStateRepository).evictFilter(MEMBER_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 정렬을_저장하고_업종을_지운_뒤_다시_넣는다() {
        service.saveFilter(MEMBER_ID, new FeedFilterSaveRequest(List.of("KOREAN", "JAPANESE"), "DISCOUNT_RATE"));

        InOrder order = inOrder(feedFilterRepository, feedFilterCategoryRepository);
        order.verify(feedFilterRepository).upsertSort(MEMBER_ID, "DISCOUNT_RATE", NOW_LOCAL);
        order.verify(feedFilterCategoryRepository).deleteAllByMemberId(MEMBER_ID);
        order.verify(feedFilterCategoryRepository).insertCategory(MEMBER_ID, "KOREAN", NOW_LOCAL);
        order.verify(feedFilterCategoryRepository).insertCategory(MEMBER_ID, "JAPANESE", NOW_LOCAL);
    }

    @Test
    void 빈_배열이면_업종을_모두_지우고_넣지_않는다() {
        service.saveFilter(MEMBER_ID, new FeedFilterSaveRequest(List.of(), "DISTANCE"));

        verify(feedFilterRepository).upsertSort(MEMBER_ID, "DISTANCE", NOW_LOCAL);
        verify(feedFilterCategoryRepository).deleteAllByMemberId(MEMBER_ID);
        verify(feedFilterCategoryRepository, never()).insertCategory(any(), anyString(), any());
    }

    // 중복을 거부하지 않고 합치는 것은 #411 에서 임시로 정했다
    @Test
    void 임시_같은_업종이_두_번_오면_하나만_넣는다() {
        service.saveFilter(MEMBER_ID, new FeedFilterSaveRequest(List.of("KOREAN", "KOREAN"), "DISTANCE"));

        verify(feedFilterCategoryRepository, times(1)).insertCategory(eq(MEMBER_ID), eq("KOREAN"), any());
    }

    @Test
    void 저장한_뒤의_필터를_조회와_같은_모양으로_돌려준다() {
        when(feedFilterRepository.findById(MEMBER_ID)).thenReturn(Optional.of(filter(FeedSort.DISCOUNT_RATE)));
        when(feedFilterCategoryRepository.findCategoryCodes(MEMBER_ID)).thenReturn(List.of("JAPANESE", "KOREAN"));

        FeedFilterResponse response = service.saveFilter(
                MEMBER_ID, new FeedFilterSaveRequest(List.of("KOREAN", "JAPANESE"), "DISCOUNT_RATE"));

        assertThat(response).isEqualTo(new FeedFilterResponse(List.of("JAPANESE", "KOREAN"), FeedSort.DISCOUNT_RATE));
    }
}
