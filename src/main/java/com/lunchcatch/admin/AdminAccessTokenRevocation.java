package com.lunchcatch.admin;

import java.time.LocalDateTime;

// DB 조회 결과만 전달하며 로그인·재발급 순번은 폐기 기준에 포함하지 않는다.
public record AdminAccessTokenRevocation(long version, LocalDateTime validAfter) { }