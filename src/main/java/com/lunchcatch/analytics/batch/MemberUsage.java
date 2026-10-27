package com.lunchcatch.analytics.batch;

import java.util.List;

/*
 * 하루 회원 사용 통계다. 찜이나 사용이 있는 회원마다 한 행을 담고 둘 다 없는 회원은 행이 없다.
 */
public record MemberUsage(List<Row> rows) {

    public record Row(long memberId, long wishCount, long redeemCount, long savedAmount) {
    }
}
