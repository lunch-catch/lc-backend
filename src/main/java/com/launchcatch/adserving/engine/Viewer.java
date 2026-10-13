package com.launchcatch.adserving.engine;

import com.launchcatch.member.contract.AgeGroup;
import com.launchcatch.member.contract.Gender;

// 피드를 받는 사용자의 위치와 노출 대상 판정 값이다
public record Viewer(double latitude, double longitude, Gender gender, AgeGroup ageGroup) {
}
