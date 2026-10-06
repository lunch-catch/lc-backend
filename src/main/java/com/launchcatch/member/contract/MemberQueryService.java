package com.launchcatch.member.contract;

import java.util.Optional;

public interface MemberQueryService {

    Optional<MemberInfo> findById(Long memberId);
}
