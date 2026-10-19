package com.launchcatch.campaign.poster.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.launchcatch.campaign.exception.CampaignException;
import com.launchcatch.campaign.poster.ForbiddenExpressionDictionary;
import com.launchcatch.campaign.poster.client.PosterImageInspection;
import com.launchcatch.campaign.poster.client.PosterImageInspector;
import com.launchcatch.campaign.poster.dto.PosterModerationOutcome;
import com.launchcatch.campaign.poster.entity.ModerationFailReason;
import com.launchcatch.campaign.poster.entity.ModerationResult;
import com.launchcatch.campaign.poster.entity.Poster;
import com.launchcatch.campaign.poster.entity.PosterModerationResult;
import com.launchcatch.campaign.poster.repository.PosterModerationResultRepository;
import com.launchcatch.campaign.poster.repository.PosterRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PosterModerationServiceTest {

    private static final Long CAMPAIGN_ID = 10L;
    private static final Long POSTER_ID = 20L;
    private static final String OBJECT_KEY = "posters/10/image.png";

    @Mock
    private PosterRepository posterRepository;

    @Mock
    private PosterModerationResultRepository posterModerationResultRepository;

    @Mock
    private PosterImageInspector posterImageInspector;

    private PosterModerationService service;

    @BeforeEach
    void setUp() {
        service = new PosterModerationService(posterRepository, posterModerationResultRepository,
                new ForbiddenExpressionDictionary(), posterImageInspector, 0.5, 200, 200);
    }

    @Test
    @DisplayName("기준을 모두 지키면 PASS 를 저장하고 돌려준다")
    void 모두_지키면_PASS() {
        givenPoster("김밥천국 - 가을 세일", "광고");
        givenImage(PosterImageInspection.read(200, 200, 0.5, List.of("오늘만 10% 할인")));

        PosterModerationOutcome outcome = service.moderate(CAMPAIGN_ID);

        assertThat(outcome.result()).isEqualTo(ModerationResult.PASS);
        assertThat(outcome.reasons()).isEmpty();
        PosterModerationResult saved = savedResult();
        assertThat(saved.getResult()).isEqualTo(ModerationResult.PASS);
        assertThat(saved.getReason()).isNull();
    }

    @Test
    @DisplayName("해상도가 기준보다 1px 작으면 FAIL 이다")
    void 해상도_경계() {
        givenPoster("김밥천국 - 가을 세일", "광고");
        givenImage(PosterImageInspection.read(199, 200, 0.1, List.of()));

        PosterModerationOutcome outcome = service.moderate(CAMPAIGN_ID);

        assertThat(outcome.reasons()).containsExactly(ModerationFailReason.RESOLUTION_TOO_LOW);
    }

    @Test
    @DisplayName("이미지 속 글자 비율이 50% 를 넘으면 FAIL 이다")
    void 텍스트_비율_경계() {
        givenPoster("김밥천국 - 가을 세일", "광고");
        givenImage(PosterImageInspection.read(300, 300, 0.5001, List.of()));

        PosterModerationOutcome outcome = service.moderate(CAMPAIGN_ID);

        assertThat(outcome.reasons()).containsExactly(ModerationFailReason.TEXT_RATIO_EXCEEDED);
    }

    @Test
    @DisplayName("광고 라벨 슬롯이 비어 있으면 FAIL 이다")
    void 광고_라벨_없음() {
        givenPoster("김밥천국 - 가을 세일", "");
        givenImage(PosterImageInspection.read(300, 300, 0.1, List.of()));

        assertThat(service.moderate(CAMPAIGN_ID).reasons()).containsExactly(ModerationFailReason.AD_LABEL_MISSING);
    }

    @Test
    @DisplayName("이미지 속 글자에 금지 표현이 있으면 FAIL 이다")
    void 이미지_속_금지_표현() {
        givenPoster("김밥천국 - 가을 세일", "광고");
        givenImage(PosterImageInspection.read(300, 300, 0.1, List.of("동네 최 저 가")));

        assertThat(service.moderate(CAMPAIGN_ID).reasons()).containsExactly(ModerationFailReason.FORBIDDEN_EXPRESSION);
    }

    @Test
    @DisplayName("포스터 제목에 금지 표현이 있으면 FAIL 이다")
    void 제목_금지_표현() {
        givenPoster("무조건 할인 가게 - 가을 세일", "광고");
        givenImage(PosterImageInspection.read(300, 300, 0.1, List.of()));

        assertThat(service.moderate(CAMPAIGN_ID).reasons()).containsExactly(ModerationFailReason.FORBIDDEN_EXPRESSION);
    }

    @Test
    @DisplayName("이미지를 읽을 수 없으면 형식 오류 사유로 FAIL 이다")
    void 이미지_형식_오류() {
        givenPoster("김밥천국 - 가을 세일", "광고");
        givenImage(PosterImageInspection.unreadable());

        assertThat(service.moderate(CAMPAIGN_ID).reasons()).containsExactly(ModerationFailReason.IMAGE_UNREADABLE);
    }

    @Test
    @DisplayName("검수 도구가 실패하면 도구 장애 사유로 FAIL 을 저장한다")
    void 도구_장애() {
        givenPoster("김밥천국 - 가을 세일", "광고");
        givenImage(PosterImageInspection.toolFailure());

        PosterModerationOutcome outcome = service.moderate(CAMPAIGN_ID);

        assertThat(outcome.result()).isEqualTo(ModerationResult.FAIL);
        assertThat(outcome.reasons()).containsExactly(ModerationFailReason.MODERATION_TOOL_FAILURE);
        assertThat(savedResult().getReason()).isEqualTo("MODERATION_TOOL_FAILURE");
    }

    @Test
    @DisplayName("걸린 사유를 모두 모아 쉼표로 저장한다")
    void 사유를_모두_모은다() {
        givenPoster("김밥천국 - 가을 세일", "");
        givenImage(PosterImageInspection.read(100, 100, 0.9, List.of("최저가")));

        PosterModerationOutcome outcome = service.moderate(CAMPAIGN_ID);

        assertThat(outcome.reasons()).containsExactly(
                ModerationFailReason.FORBIDDEN_EXPRESSION,
                ModerationFailReason.AD_LABEL_MISSING,
                ModerationFailReason.RESOLUTION_TOO_LOW,
                ModerationFailReason.TEXT_RATIO_EXCEEDED);
        assertThat(savedResult().getReason())
                .isEqualTo("FORBIDDEN_EXPRESSION,AD_LABEL_MISSING,RESOLUTION_TOO_LOW,TEXT_RATIO_EXCEEDED");
    }

    @Test
    @DisplayName("포스터가 없으면 검수하지 않는다")
    void 포스터_없음() {
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.moderate(CAMPAIGN_ID)).isInstanceOf(CampaignException.class);
    }

    private void givenPoster(String title, String adLabel) {
        String slotValues = "{\"eventName\":{\"type\":\"text\",\"value\":\"김밥천국\"},"
                + "\"discount\":{\"type\":\"text\",\"value\":\"10% 할인\"},"
                + "\"period\":{\"type\":\"text\",\"value\":\"10.20 ~ 10.31\"},"
                + "\"adLabel\":{\"type\":\"text\",\"value\":\"" + adLabel + "\"},"
                + "\"image\":{\"type\":\"image\",\"value\":\"https://cdn.example.com/최저가.png\"}}";
        Poster poster = Poster.create(CAMPAIGN_ID, 1L, title, slotValues, "<div></div>", OBJECT_KEY);
        ReflectionTestUtils.setField(poster, "id", POSTER_ID);
        when(posterRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(Optional.of(poster));
    }

    private void givenImage(PosterImageInspection inspection) {
        when(posterImageInspector.inspect(OBJECT_KEY)).thenReturn(inspection);
    }

    private PosterModerationResult savedResult() {
        ArgumentCaptor<PosterModerationResult> captor = ArgumentCaptor.forClass(PosterModerationResult.class);
        verify(posterModerationResultRepository).save(captor.capture());
        return captor.getValue();
    }
}
