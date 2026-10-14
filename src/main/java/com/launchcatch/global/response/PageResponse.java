package com.launchcatch.global.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/*
 * 목록 조회의 공통 응답이며 ResponseEnvelope 의 data 자리에 들어간다.
 * 목록만 돌려주면 클라이언트가 다음 페이지가 있는지 알 수 없어서 조회 조건과 전체 규모를 함께 싣는다.
 * 전체 페이지 수는 두지 않는다. totalElements 와 size 로 계산되는 값이라, 담아 두면 서로 어긋날 자리만 생긴다.
 */
public record PageResponse<T>(
        @Schema(description = "현재 페이지 항목. 결과가 없으면 빈 배열입니다.")
        List<T> items,
        @Schema(description = "0부터 시작하는 현재 페이지 번호입니다.", example = "0")
        int page,
        @Schema(description = "요청한 페이지 크기입니다.", example = "20")
        int size,
        @Schema(description = "조회 조건에 맞는 전체 항목 수입니다.", example = "42")
        long totalElements
) {

    // 레포지토리가 돌려준 Page 를 그대로 옮긴다
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }

    /*
     * Page 를 거치지 않고 직접 조립할 때 쓴다.
     * 집계 쿼리를 따로 돌렸거나 여러 소스를 합쳐 목록을 만든 경우가 그렇다.
     */
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        return new PageResponse<>(items, page, size, totalElements);
    }
}
