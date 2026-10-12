package com.launchcatch.member.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public interface MemberRepositoryCustom {

    /*
     * 정렬은 sortKey 와 direction 으로 받는다. Pageable 의 Sort 는 쓰지 않는다.
     * 값이 같은 행은 회원 번호를 direction 과 같은 방향으로 정렬해 페이지가 흔들리지 않게 한다.
     */
    Page<MemberAdminRow> searchForAdmin(
            MemberSearchCondition condition, MemberSortKey sortKey, Sort.Direction direction, Pageable pageable);
}
