package com.launchcatch.adserving.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.launchcatch.adserving.engine.FeedAssembler.Feed;
import com.launchcatch.adserving.engine.FeedAssembler.Option;
import com.launchcatch.adserving.engine.FeedAssembler.Slot;
import com.launchcatch.adserving.engine.entity.DiscountType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("피드 자리 배정")
class FeedAssemblerTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 1, 9, 0);

    // 캠페인 ID 가 작을수록 먼저 만든 캠페인이다
    private static Option option(long campaignId, double distanceM, double progressRate, double preference,
                                 DiscountType discountType, int discountValue) {
        return new Option(campaignId, CREATED_AT.plusMinutes(campaignId), discountType, discountValue, distanceM,
                progressRate, preference);
    }

    private static Option option(long campaignId, double distanceM, double progressRate) {
        return option(campaignId, distanceM, progressRate, 0.0, DiscountType.PERCENT, 20);
    }

    private static List<Long> campaignIds(Feed feed) {
        return feed.slots().stream().map(slot -> slot.option().campaignId()).toList();
    }

    @Test
    void 배분_3장을_1_4_7번에_진행률이_낮은_순으로_둔다() {
        List<Option> options = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            options.add(option(id, 100 * id, 0.9));
        }
        options.set(9, option(10, 1000, 0.1));
        options.set(8, option(9, 900, 0.2));
        options.set(7, option(8, 800, 0.3));

        Feed feed = FeedAssembler.assemble(options, FeedSort.DISTANCE);

        assertThat(feed.slots()).filteredOn(slot -> slot.slotType() == SlotType.ALLOCATION)
                .extracting(Slot::position, slot -> slot.option().campaignId())
                .containsExactly(
                        tuple(1, 10L),
                        tuple(4, 9L),
                        tuple(7, 8L));
    }

    @Test
    void 진행률이_같으면_캠페인을_먼저_만든_순으로_배분한다() {
        Feed feed = FeedAssembler.assemble(List.of(option(3, 100, 0.5), option(1, 100, 0.5), option(2, 100, 0.5)),
                FeedSort.DISTANCE);

        assertThat(campaignIds(feed)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void 후보가_10장_이상_모이는_가장_작은_반경을_쓰고_그_밖은_뺀다() {
        List<Option> options = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            options.add(option(id, 1500, 0.5));
        }
        options.add(option(11, 2500, 0.0));

        Feed feed = FeedAssembler.assemble(options, FeedSort.DISTANCE);

        assertThat(feed.radiusM()).isEqualTo(2000);
        assertThat(campaignIds(feed)).hasSize(10).doesNotContain(11L);
    }

    @Test
    void 가장_큰_반경에서도_모자라면_5000m_안의_후보를_있는_만큼_채운다() {
        Feed feed = FeedAssembler.assemble(List.of(option(1, 4900, 0.5), option(2, 300, 0.5)), FeedSort.DISTANCE);

        assertThat(feed.radiusM()).isEqualTo(5000);
        assertThat(feed.slots()).extracting(Slot::position).containsExactly(1, 2);
    }

    @Test
    void 후보가_없으면_자리가_비고_가장_큰_반경이다() {
        Feed feed = FeedAssembler.assemble(List.of(), FeedSort.DISTANCE);

        assertThat(feed.slots()).isEmpty();
        assertThat(feed.radiusM()).isEqualTo(5000);
    }

    @Test
    void 배분이_다_차면_관련성이_나머지_자리를_채운다() {
        List<Option> options = List.of(option(1, 100, 0.1), option(2, 200, 0.2), option(3, 300, 0.3),
                option(4, 400, 0.9), option(5, 500, 0.9));

        Feed feed = FeedAssembler.assemble(options, FeedSort.DISTANCE);

        assertThat(feed.slots()).extracting(Slot::slotType).containsExactly(
                SlotType.ALLOCATION, SlotType.RELEVANCE, SlotType.RELEVANCE, SlotType.ALLOCATION, SlotType.ALLOCATION);
    }

    @Test
    void 관련성_7장은_점수가_높은_것을_고르고_거리순이면_가까운_순으로_늘어놓는다_임시() {
        List<Option> options = new ArrayList<>();
        for (long id = 1; id <= 3; id++) {
            options.add(option(id, 900, 0.0));
        }
        for (long id = 4; id <= 11; id++) {
            options.add(option(id, 100 * (12 - id), 0.9, id == 4 ? 0.0 : 1.0, DiscountType.PERCENT, 20));
        }

        Feed feed = FeedAssembler.assemble(options, FeedSort.DISTANCE);

        assertThat(feed.slots()).filteredOn(slot -> slot.slotType() == SlotType.RELEVANCE)
                .extracting(slot -> slot.option().campaignId())
                .containsExactly(11L, 10L, 9L, 8L, 7L, 6L, 5L);
    }

    @Test
    void 할인율순이면_관련성_7장을_할인이_큰_순으로_늘어놓는다_임시() {
        List<Option> options = List.of(
                option(1, 100, 0.0), option(2, 100, 0.0), option(3, 100, 0.0),
                option(4, 100, 0.9, 0.0, DiscountType.PERCENT, 10),
                option(5, 100, 0.9, 0.0, DiscountType.AMOUNT, 5000),
                option(6, 100, 0.9, 0.0, DiscountType.PERCENT, 30));

        Feed feed = FeedAssembler.assemble(options, FeedSort.DISCOUNT_RATE);

        assertThat(feed.slots()).filteredOn(slot -> slot.slotType() == SlotType.RELEVANCE)
                .extracting(slot -> slot.option().campaignId())
                .containsExactly(5L, 6L, 4L);
    }

    @Test
    void 할인_점수는_할인율이거나_1만원을_1로_본_금액이다_임시() {
        assertThat(FeedAssembler.discountScore(option(1, 0, 0, 0, DiscountType.PERCENT, 25))).isEqualTo(0.25);
        assertThat(FeedAssembler.discountScore(option(1, 0, 0, 0, DiscountType.AMOUNT, 3000))).isEqualTo(0.3);
        assertThat(FeedAssembler.discountScore(option(1, 0, 0, 0, DiscountType.AMOUNT, 20000))).isEqualTo(1.0);
    }
}
