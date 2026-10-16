package com.launchcatch.billing.repository;

// 점주 한 명의 잔액이며 DEDUCT 를 뺀 원장 합이다
public record OwnerBalanceRow(Long ownerId, Long balance) {
}
