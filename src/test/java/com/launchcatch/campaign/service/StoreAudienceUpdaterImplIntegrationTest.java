package com.launchcatch.campaign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.StoreAudienceCell;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.StoreAudience;
import com.launchcatch.campaign.entity.StoreAudienceGender;
import com.launchcatch.campaign.repository.StoreAudienceRepository;
import com.launchcatch.global.config.ClockConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * StoreAudienceUpdaterImpl이 실제 MySQL에 보내는 다중 행 INSERT ... ON DUPLICATE KEY UPDATE가
 * 진짜로 덮어쓰는지, 유일 키가 다르면 새 행으로 쌓이는지를 확인한다. 전에는 이 SQL이
 * StoreAudienceRepository의 @Query였지만, 왕복을 줄이려고 다중 행 SQL로 바뀌면서
 * StoreAudienceUpdaterImpl로 옮겨졌다(#186 리뷰 반영).
 */
class StoreAudienceUpdaterImplIntegrationTest extends com.launchcatch.campaign.AbstractCampaignIntegrationTest {

    @Autowired
    private StoreAudienceUpdaterImpl updater;

    @Autowired
    private StoreAudienceRepository storeAudienceRepository;


    @Test
    void 같은_유일_키로_두_번_부르면_새_행이_아니라_덮어쓴다() {
        Instant firstInstant = Instant.parse("2026-10-12T00:00:00Z");
        Instant secondInstant = Instant.parse("2026-10-13T00:00:00Z");
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(firstInstant, secondInstant);

        updater.overwrite(List.of(
                new StoreAudienceCell(1L, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 10, LocalDate.of(2026, 10, 12))));
        updater.overwrite(List.of(
                new StoreAudienceCell(1L, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 25, LocalDate.of(2026, 10, 13))));

        assertThat(storeAudienceRepository.findAll()).hasSize(1);
        StoreAudience row = storeAudienceRepository.findAll().get(0);
        assertThat(row.getMemberCount()).isEqualTo(25);
        assertThat(row.getBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(row.getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(firstInstant, ClockConfig.ZONE));
        assertThat(row.getUpdatedAt()).isEqualTo(LocalDateTime.ofInstant(secondInstant, ClockConfig.ZONE));
    }

    @Test
    void 다른_유일_키는_다른_행으로_쌓인다() {
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T00:00:00Z"));

        updater.overwrite(List.of(
                new StoreAudienceCell(1L, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 10, LocalDate.of(2026, 10, 13)),
                new StoreAudienceCell(1L, 1000, TargetGender.FEMALE, AgeGroup.AGE_20S, 5, LocalDate.of(2026, 10, 13)),
                new StoreAudienceCell(2L, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 7, LocalDate.of(2026, 10, 13))));

        assertThat(storeAudienceRepository.findAll()).hasSize(3);
    }

    @Test
    void AGE_50_PLUS도_저장된다() {
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T00:00:00Z"));

        updater.overwrite(List.of(
                new StoreAudienceCell(1L, 1000, TargetGender.MALE, AgeGroup.AGE_50_PLUS, 10, LocalDate.of(2026, 10, 13))));

        assertThat(storeAudienceRepository.findAll())
                .extracting(StoreAudience::getAgeGroup)
                .containsExactly(AgeGroup.AGE_50_PLUS);
    }

    @Test
    void 이백오십행이_넘으면_여러_SQL_문으로_나눠_보내도_전부_저장된다() {
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T00:00:00Z"));

        List<StoreAudienceCell> cells = IntStream.range(1, 301)
                .mapToObj(storeId -> new StoreAudienceCell(
                        storeId, 1000, TargetGender.MALE, AgeGroup.AGE_20S, 1, LocalDate.of(2026, 10, 13)))
                .toList();

        updater.overwrite(cells);

        assertThat(storeAudienceRepository.findAll()).hasSize(300);
    }

    @Test
    void ALL로도_저장된다() {
        when(clock.getZone()).thenReturn(ClockConfig.ZONE);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T00:00:00Z"));

        updater.overwrite(List.of(
                new StoreAudienceCell(1L, 1000, TargetGender.ALL, AgeGroup.AGE_20S, 10, LocalDate.of(2026, 10, 13))));

        assertThat(storeAudienceRepository.findAll())
                .extracting(StoreAudience::getGender)
                .containsExactly(StoreAudienceGender.ALL);
    }
}
