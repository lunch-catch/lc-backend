package com.launchcatch.campaign.template.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.template.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.TemplateSlotValidator;
import com.launchcatch.campaign.template.TemplateStructureValidator;
import com.launchcatch.campaign.template.config.BedrockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/*
 * bedrock.model-id 유무로 TemplateHtmlGenerator 빈이 Fake/Bedrock 중 하나만 뜨는지 확인한다
 * (#182 완료 조건: 로컬 프로필은 가짜 구현을 쓴다). DB/Redis 가 필요 없는 빈만 올려서 확인한다.
 */
class TemplateHtmlGeneratorWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    BedrockConfig.class,
                    FakeTemplateHtmlGenerator.class,
                    BedrockTemplateHtmlGenerator.class,
                    TemplateHtmlSanitizer.class,
                    TemplateSlotValidator.class,
                    TemplateStructureValidator.class);

    @Test
    void bedrock_model_id가_없으면_Fake만_뜬다() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TemplateHtmlGenerator.class);
            assertThat(context.getBean(TemplateHtmlGenerator.class)).isInstanceOf(FakeTemplateHtmlGenerator.class);
        });
    }

    @Test
    void bedrock_model_id가_있으면_Bedrock만_뜬다() {
        contextRunner
                .withPropertyValues("bedrock.model-id=openai.gpt-oss-120b-1:0", "bedrock.region=ap-northeast-1")
                .run(context -> {
                    assertThat(context).hasSingleBean(TemplateHtmlGenerator.class);
                    assertThat(context.getBean(TemplateHtmlGenerator.class))
                            .isInstanceOf(BedrockTemplateHtmlGenerator.class);
                });
    }

    /*
     * 예전에 "없음"을 havingValue 문자열 비교로 흉내낼 때, 그 문자열 자체가 실제 값으로
     * 들어오면 Fake 조건도 함께 참이 되어 두 빈이 같이 뜨는 사고가 있었다(#182 코드 리뷰로
     * 발견). 환경에 그 키가 있는지를 직접 보는 지금 조건에서는 어떤 값이 와도 Bedrock만
     * 떠야 한다.
     */
    @Test
    void bedrock_model_id가_과거_sentinel_문자열과_같아도_Bedrock만_뜬다() {
        contextRunner
                .withPropertyValues("bedrock.model-id=__absent__", "bedrock.region=ap-northeast-1")
                .run(context -> {
                    assertThat(context).hasSingleBean(TemplateHtmlGenerator.class);
                    assertThat(context.getBean(TemplateHtmlGenerator.class))
                            .isInstanceOf(BedrockTemplateHtmlGenerator.class);
                });
    }

    @Test
    void bedrock_model_id가_false면_Fake만_뜬다() {
        contextRunner
                .withPropertyValues("bedrock.model-id=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(TemplateHtmlGenerator.class);
                    assertThat(context.getBean(TemplateHtmlGenerator.class))
                            .isInstanceOf(FakeTemplateHtmlGenerator.class);
                });
    }
}
