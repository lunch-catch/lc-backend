package com.lunchcatch.billing.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;

/*
 * 승인을 요청받은 순간에 그 주문의 결제 식별자를 다른 커넥션으로 읽어 둔다.
 * 결제 식별자 기록이 호출 전에 커밋되지 않았다면 여기서 NULL 이 보인다.
 */
public class CommitCheckingGateway extends FakePaymentGateway {

    private final JdbcTemplate jdbc;
    private final List<String> pgTidsSeenAtCall = new ArrayList<>();
    private volatile Consumer<String> beforeApproval = paymentKey -> { };

    /*
     * 커밋 확인은 꺼진 채로 시작한다. billing 통합 테스트가 이 빈 하나를 공유하므로, 켜 둔 채로
     * 두면 커밋 확인이 필요 없는 테스트의 승인 호출마다 다른 커넥션으로 조회가 끼어든다.
     */
    private volatile boolean checkingCommit;

    public CommitCheckingGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 승인 호출마다 그 주문의 결제 식별자를 읽어 둔다. 커밋 확인을 보는 테스트만 켠다. */
    public void checkCommit() {
        checkingCommit = true;
    }

    @Override
    public PaymentGatewayApproval confirm(String paymentKey, String orderId, long amount) {
        if (checkingCommit) {
            synchronized (this) {
                pgTidsSeenAtCall.add(jdbc.queryForObject(
                        "select pg_tid from payment where order_id = ?", String.class, orderId));
            }
        }
        beforeApproval.accept(paymentKey);
        synchronized (this) {
            return super.confirm(paymentKey, orderId, amount);
        }
    }

    public void beforeApproval(Consumer<String> action) {
        beforeApproval = action;
    }

    public synchronized List<String> pgTidsSeenAtCall() {
        return List.copyOf(pgTidsSeenAtCall);
    }

    // 빈이 테스트 사이에 공유되므로 앞 테스트에서 본 값을 지운다
    public synchronized void clearSeen() {
        pgTidsSeenAtCall.clear();
        beforeApproval = paymentKey -> { };
        checkingCommit = false;
    }
}
