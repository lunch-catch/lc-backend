package com.lunchcatch.billing.dto;

import java.time.LocalDateTime;

// 잔액을 바꾸는 원장 한 줄이다. DEDUCT 는 잔액을 바꾸지 않으므로 이 목록에 들어오지 않는다
public record BalanceStep(Long id, LocalDateTime createdAt, Long amount) {
}
