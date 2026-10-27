package com.lunchcatch.campaign.template.client;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/*
 * bedrock.model-id가 비어 있거나(미설정) "false"일 때만 참이다. BedrockConfig와
 * BedrockTemplateHtmlGenerator가 쓰는 @ConditionalOnProperty(name = "bedrock.model-id")는
 * "값이 있고 false가 아니면 통과"라, 이 조건은 그 반대를 환경값으로 직접 판정해 맞춘다.
 *
 * 전에는 havingValue에 실제 모델 ID와 겹칠 수 없는 문자열("__absent__")을 넣어 "없음"을
 * 흉내냈는데, 그 문자열 자체가 실수로 설정값이 되면 두 조건이 동시에 참이 되어 같은 타입의
 * 빈이 둘 다 뜨는 사고가 재발할 수 있었다(#182 코드 리뷰로 발견). 문자열 비교 대신 환경에
 * 그 키가 실제로 있는지를 직접 확인해 이 위험을 없앤다.
 */
public class BedrockModelIdAbsentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty("bedrock.model-id");
        return value == null || "false".equalsIgnoreCase(value);
    }
}
