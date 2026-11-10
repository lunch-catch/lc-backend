package com.lunchcatch.global.config;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;

/*
 * AWS SDK 호출(Bedrock Runtime, S3, Rekognition)마다 걸린 시간을 서비스, 작업, 모델, 결과로 나눠 남긴다(#1166).
 * SDK 가 클래스패스의 software/amazon/awssdk/global/handlers/execution.interceptors 를 읽어 모든 클라이언트에 붙인다.
 * 그래서 Bedrock 을 부르는 캠페인 코드를 고치지 않는다.
 * SDK 가 이 클래스를 직접 만들어 스프링 빈을 주입받지 못한다. Boot 가 앱의 레지스트리를 넣어 두는 Micrometer 전역 레지스트리에 남긴다.
 * 시간은 재시도까지 포함한 호출 한 번이다. 이 앱의 SDK 클라이언트는 SDK 재시도를 끄거나 기본값으로 둔다.
 */
public class AwsSdkCallMetricsInterceptor implements ExecutionInterceptor {

    static final String METRIC = "aws.sdk.calls";
    // Bedrock 의 호출 시간 제한(30초)보다 넉넉히 둬 느린 호출이 마지막 버킷에 뭉치지 않게 한다
    private static final Duration MAX_EXPECTED = Duration.ofSeconds(60);
    private static final ExecutionAttribute<Long> STARTED_AT = new ExecutionAttribute<>("lunchcatch.startedAtNanos");

    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
        attributes.putAttribute(STARTED_AT, System.nanoTime());
    }

    @Override
    public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
        record(context.request(), attributes, "success");
    }

    @Override
    public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
        record(context.request(), attributes, "failure");
    }

    private void record(SdkRequest request, ExecutionAttributes attributes, String outcome) {
        Long startedAt = attributes.getAttribute(STARTED_AT);
        if (startedAt == null) {
            return;
        }
        Timer.builder(METRIC)
                .description("AWS SDK 호출 한 번의 시간. p99 를 계산하는 버킷을 내보낸다")
                .tag("service", String.valueOf(attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME)))
                .tag("operation", String.valueOf(attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME)))
                // LLM 은 모델마다 정상 시간이 크게 달라 모델로 나눈다. 모델이 없는 호출은 none 이다
                .tag("model", request.getValueForField("modelId", String.class).orElse("none"))
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .maximumExpectedValue(MAX_EXPECTED)
                .register(Metrics.globalRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startedAt));
    }
}
