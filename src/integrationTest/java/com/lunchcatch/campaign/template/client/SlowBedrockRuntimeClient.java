package com.lunchcatch.campaign.template.client;

import com.lunchcatch.campaign.template.SlowCallMeasureSettings.Mode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;

/*
 * 정한 시간만큼 늦게 응답하는 Bedrock 클라이언트 스텁이다(#1269).
 *
 * 벌크헤드가 BedrockTemplateHtmlGenerator 안의 converse 호출을 감싸므로, 생성기는 그대로 두고 이 클라이언트만 바꾼다.
 * campaign 의 공용 컨텍스트가 지연 없이 꽂아 두고, 측정이 조건마다 지연과 조건을 바꾼다. 같은 순간에 진행 중인 호출 수의
 * 최대와 호출 횟수를 센다.
 *
 * CORRECTION_RETRY 조건에서는 첫 호출에 슬롯이 빠진 HTML 을 준다. 생성기가 교정 지시를 덧붙여 다시 부르면 대화가
 * 3개 메시지로 늘어나므로, 메시지가 1개인 호출을 첫 호출로 본다.
 */
public class SlowBedrockRuntimeClient implements BedrockRuntimeClient {

    // 슬롯 검증을 모두 통과하는 HTML 이다. FakeTemplateHtmlGenerator 의 HTML 과 같은 모양이다.
    private static final String VALID_HTML = """
            <div class="poster" style="background-color:#FFFFFF;color:#000000">
              <h1 data-slot="eventName">이벤트명</h1>
              <p data-slot="discount">할인 내용</p>
              <p data-slot="period">기간</p>
              <img data-slot="image" alt="메뉴 이미지">
            </div>""";

    // discount 슬롯이 빠져 슬롯 검증에 걸린다. 생성기가 교정 지시를 붙여 한 번 더 부르게 한다.
    private static final String SLOT_MISSING_HTML = """
            <div class="poster" style="background-color:#FFFFFF;color:#000000">
              <h1 data-slot="eventName">이벤트명</h1>
              <p data-slot="period">기간</p>
              <img data-slot="image" alt="메뉴 이미지">
            </div>""";

    private static final String MESSAGE = "요청하신 분위기로 템플릿을 만들었어요.";

    private volatile long delayMillis;
    private volatile Mode mode;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final Set<String> firstCallCompletedPrompts = ConcurrentHashMap.newKeySet();

    public SlowBedrockRuntimeClient(long delayMillis, Mode mode) {
        reset(delayMillis, mode);
    }

    // 조건을 바꾸고 센 값을 비운다. 회차를 시작하기 전에 부른다.
    public void reset(long delayMillis, Mode mode) {
        this.delayMillis = delayMillis;
        this.mode = mode;
        inFlight.set(0);
        maxInFlight.set(0);
        calls.set(0);
        firstCallCompletedPrompts.clear();
    }

    @Override
    public ConverseResponse converse(ConverseRequest request) {
        calls.incrementAndGet();
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("스텁 지연 중에 끊겼다", e);
        } finally {
            inFlight.decrementAndGet();
        }

        boolean firstCall = request.messages().size() == 1;
        if (firstCall) {
            firstCallCompletedPrompts.add(request.messages().get(0).content().get(0).text());
        }
        boolean slotMissing = mode == Mode.CORRECTION_RETRY && firstCall;
        return response("```html\n" + (slotMissing ? SLOT_MISSING_HTML : VALID_HTML) + "\n```\n" + MESSAGE);
    }

    public int maxInFlight() {
        return maxInFlight.get();
    }

    public int calls() {
        return calls.get();
    }

    // 첫 호출이 끝난 요청의 요청 문구다. 첫 호출은 성공했는데 두 번째 호출이 거절된 요청을 가를 때 쓴다.
    public Set<String> firstCallCompletedPrompts() {
        return Set.copyOf(firstCallCompletedPrompts);
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public void close() {
        // 연결을 열지 않으므로 닫을 것이 없다.
    }

    private static ConverseResponse response(String text) {
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText(text))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason(StopReason.END_TURN)
                .build();
    }
}
