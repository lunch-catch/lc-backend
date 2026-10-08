package com.launchcatch.member.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MemberRepositoryCustom {

    Page<MemberAdminRow> searchForAdmin(MemberSearchCondition condition, Pageable pageable);
}
