package com.launchcatch.admin.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AdminRole {

    ADMIN("관리자"),
    SUPER_ADMIN("최고관리자");

    private final String displayName;

}