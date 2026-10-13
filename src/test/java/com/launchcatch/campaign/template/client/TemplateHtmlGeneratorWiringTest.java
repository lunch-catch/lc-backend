package com.launchcatch.campaign.template.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.launchcatch.campaign.template.config.BedrockConfig;
import com.launchcatch.campaign.template.service.TemplateHtmlSanitizer;
import com.launchcatch.campaign.template.service.TemplateSlotValidator;
import com.launchcatch.campaign.template.service.TemplateStructureValidator;
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
}
