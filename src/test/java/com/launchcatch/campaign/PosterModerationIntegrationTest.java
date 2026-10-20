package com.launchcatch.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import com.launchcatch.campaign.contract.AgeGroup;
import com.launchcatch.campaign.contract.DiscountTargetType;
import com.launchcatch.campaign.contract.DiscountType;
import com.launchcatch.campaign.contract.TargetGender;
import com.launchcatch.campaign.entity.Campaign;
import com.launchcatch.campaign.poster.client.PosterImageInspection;
import com.launchcatch.campaign.poster.entity.ModerationResult;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.entity.PosterModerationResult;
import com.launchcatch.campaign.poster.repository.PosterModerationResultRepository;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import com.launchcatch.campaign.poster.service.PosterModerationService;
import com.launchcatch.campaign.template.entity.Template;
import com.launchcatch.campaign.template.repository.TemplateRepository;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * 검수 결과가 poster_moderation_result 에 실제로 들어가는지 본다(#535).
 * 엔티티 매핑과 chk_poster_moderation_result(FAIL 은 사유 필수) 제약은 단위 테스트로 확인되지 않는다.
 * 외부 호출(S3, Rekognition)은 PosterImageInspector 를 스텁해 부르지 않는다.
 */
class PosterModerationIntegrationTest extends AbstractCampaignIntegrationTest {

    @Autowired
    private PosterModerationService posterModerationService;

    @Autowired
    private PosterRepository posterRepository;

    @Autowired
    private PosterModerationResultRepository posterModerationResultRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Test
    void PASS_결과를_저장한다() {
        Poster poster = createPoster("김밥천국");
        doReturn(PosterImageInspection.read(300, 300, 0.1, List.of()))
                .when(posterImageInspector).inspect(any());

        posterModerationService.moderate(poster.getCampaignId());

        List<PosterModerationResult> saved = posterModerationResultRepository.findByPosterIdOrderByIdDesc(poster.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getResult()).isEqualTo(ModerationResult.PASS);
        assertThat(saved.getFirst().getReason()).isNull();
    }

    @Test
    void FAIL_결과를_사유와_함께_저장한다() {
        Poster poster = createPoster("최저가");
        doReturn(PosterImageInspection.toolFailure()).when(posterImageInspector).inspect(any());

        posterModerationService.moderate(poster.getCampaignId());

        List<PosterModerationResult> saved = posterModerationResultRepository.findByPosterIdOrderByIdDesc(poster.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getResult()).isEqualTo(ModerationResult.FAIL);
        assertThat(saved.getFirst().getReason()).isEqualTo("FORBIDDEN_EXPRESSION,MODERATION_TOOL_FAILURE");
    }

    private Poster createPoster(String eventName) {
        Campaign campaign = campaignRepository.saveAndFlush(Campaign.create(
                1L, System.nanoTime(),
                DiscountTargetType.ALL, DiscountType.AMOUNT, 10, null,
                50, LocalTime.of(12, 0), LocalTime.of(13, 30),
                null, null,
                1000, TargetGender.ALL, List.of(AgeGroup.AGE_20S),
                null, null, null,
                LocalDateTime.now()));
        Template template = templateRepository.saveAndFlush(Template.createDraft("가을 신메뉴"));
        String slotValues = "{\"eventName\":{\"type\":\"text\",\"value\":\"" + eventName + "\"},"
                + "\"image\":{\"type\":\"image\",\"value\":\"https://cdn.example.com/a.png\"}}";
        return posterRepository.saveAndFlush(Poster.create(
                campaign.getId(), template.getId(), "김밥천국 - 가을 신메뉴", slotValues, "<div></div>",
                "posters/" + campaign.getId() + "/image.png"));
    }
}
