package com.lunchcatch.campaign.template.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.lunchcatch.campaign.template.SlowCallMeasureSettings.Mode;
import com.lunchcatch.campaign.template.TemplateHtmlSanitizer;
import com.lunchcatch.campaign.template.TemplateSlotValidator;
import com.lunchcatch.campaign.template.TemplateStructureValidator;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * 스텁 클라이언트(#1269)가 실제 BedrockTemplateHtmlGenerator 를 거쳐 약속대로 동작하는지 본다.
 * 컨텍스트를 띄우지 않고 생성기를 직접 만든다. 지연은 측정보다 훨씬 짧게 둬서 몇 초 안에 끝난다.
 */
class SlowBedrockRuntimeClientTest {

    private static final long DELAY_MILLIS = 300L;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static BedrockTemplateHtmlGenerator generator(SlowBedrockRuntimeClient client) {
        BedrockTemplateHtmlGenerator generator = new BedrockTemplateHtmlGenerator(
                client,
                new TemplateHtmlSanitizer(),
                new TemplateSlotValidator(),
                new TemplateStructureValidator(),
                BulkheadRegistry.of(BulkheadConfig.custom()
                        .maxConcurrentCalls(5)
                        .maxWaitDuration(Duration.ZERO)
                        .build()),
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(generator, "modelId", "stub");
        return generator;
    }

    @Test
    void 한_번에_통과하는_조건은_정한_시간만큼_늦게_한_번만_응답한다() {
        SlowBedrockRuntimeClient client = new SlowBedrockRuntimeClient(DELAY_MILLIS, Mode.SINGLE);

        long start = System.nanoTime();
        GeneratedTemplate result = generator(client).generate("요청 1");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result.html()).contains("data-slot=\"discount\"");
        assertThat(client.calls()).isEqualTo(1);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(DELAY_MILLIS);
        assertThat(client.firstCallCompletedPrompts()).containsExactly("요청 1");
    }

    @Test
    void 교정_재시도_조건은_첫_응답이_슬롯_검증에_걸려_두_번_부른다() {
        SlowBedrockRuntimeClient client = new SlowBedrockRuntimeClient(DELAY_MILLIS, Mode.CORRECTION_RETRY);

        long start = System.nanoTime();
        GeneratedTemplate result = generator(client).generate("요청 1");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result.html()).contains("data-slot=\"discount\"");
        assertThat(client.calls()).isEqualTo(2);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(2 * DELAY_MILLIS);
        assertThat(client.firstCallCompletedPrompts()).containsExactly("요청 1");
    }

    @Test
    void 겹친_호출의_최대_수를_세고_reset_하면_비운다() throws Exception {
        SlowBedrockRuntimeClient client = new SlowBedrockRuntimeClient(DELAY_MILLIS, Mode.SINGLE);
        BedrockTemplateHtmlGenerator generator = generator(client);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<GeneratedTemplate>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String prompt = "요청 " + i;
            futures.add(executor.submit(() -> {
                start.await();
                return generator.generate(prompt);
            }));
        }
        start.countDown();
        for (Future<GeneratedTemplate> future : futures) {
            future.get();
        }

        assertThat(client.maxInFlight()).isEqualTo(3);
        assertThat(client.calls()).isEqualTo(3);

        client.reset(DELAY_MILLIS, Mode.CORRECTION_RETRY);

        assertThat(client.maxInFlight()).isZero();
        assertThat(client.calls()).isZero();
        assertThat(client.firstCallCompletedPrompts()).isEmpty();
    }
}
