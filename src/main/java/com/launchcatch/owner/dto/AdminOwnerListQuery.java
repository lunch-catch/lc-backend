package com.launchcatch.owner.dto;

import com.launchcatch.owner.entity.OwnerStatus;
import java.time.LocalDate;

public record AdminOwnerListQuery(OwnerStatus status, LocalDate joinedFrom, LocalDate joinedTo,
                                  String keyword, int page) {

    public AdminOwnerListQuery {
        if (page < 0) {
            throw new IllegalArgumentException("페이지 번호는 0 이상이어야 합니다.");
        }
        if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
            throw new IllegalArgumentException("가입일 시작 날짜가 종료 날짜보다 늦을 수 없습니다.");
        }
        keyword = keyword == null ? null : keyword.strip();
        if (keyword != null && keyword.isEmpty()) {
            keyword = null;
        }
    }
}
