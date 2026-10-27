package com.lunchcatch.adserving.feed.service;

import com.lunchcatch.adserving.feed.repository.ServeRecord;

/*
 * 내려준 카드 한 장과 그 배정 근거다. 근거는 serve_log 에만 남기고 serve 기록(Valkey)에는 넣지 않는다(#977).
 * progressRate 는 신규 우선 보정 전 진행률이고 예산을 읽지 못했으면 null 이다.
 */
public record ServedCard(ServeRecord record, Double progressRate, double relevanceScore) {
}
