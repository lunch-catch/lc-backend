package com.lunchcatch.adserving.engine;

import java.time.LocalDate;

/*
 * 그날 후보가 바뀌었다는 서버 사이 알림이다(#1042). 보내는 쪽은 CandidateChangeNotifier, 받는 쪽은 CandidateChangeSubscriber 다.
 * 메시지는 "영업일:캠페인 ID" 이고 그날 전체가 바뀌었으면 캠페인 자리에 * 를 둔다. campaignId 가 null 이면 그날 전체다.
 */
public record CandidateChange(LocalDate businessDate, Long campaignId) {

    public static final String CHANNEL = "adserving:candidate:changed";
    private static final String WHOLE_DAY = "*";

    public static CandidateChange one(LocalDate businessDate, Long campaignId) {
        return new CandidateChange(businessDate, campaignId);
    }

    public static CandidateChange wholeDay(LocalDate businessDate) {
        return new CandidateChange(businessDate, null);
    }

    public static CandidateChange parse(String message) {
        int colon = message.indexOf(':');
        String target = message.substring(colon + 1);
        return new CandidateChange(LocalDate.parse(message.substring(0, colon)),
                WHOLE_DAY.equals(target) ? null : Long.valueOf(target));
    }

    public String message() {
        return businessDate + ":" + (campaignId == null ? WHOLE_DAY : campaignId);
    }
}
