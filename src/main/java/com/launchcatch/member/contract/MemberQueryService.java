package com.launchcatch.member.contract;

import java.util.List;
import java.util.Optional;

public interface MemberQueryService {

    /** findAllById 가 한 번에 받는 회원 ID 의 최대 개수다. */
    int MAX_FIND_ALL_SIZE = 1000;

    Optional<MemberInfo> findById(Long memberId);

    /** 프로필을 읽지 않고 현재 회원 상태를 조회하며 없는 회원은 빈 결과를 반환한다. */
    Optional<MemberStatus> findCurrentStatus(Long memberId);

    /**
     * 회원 PK 목록으로 한 번에 조회한다. 없는 회원은 결과에서 빠지고 순서는 보장하지 않는다.
     * 목록이 MAX_FIND_ALL_SIZE 를 넘으면 IllegalArgumentException 을 던진다.
     */
    List<MemberInfo> findAllById(List<Long> memberIds);
}
