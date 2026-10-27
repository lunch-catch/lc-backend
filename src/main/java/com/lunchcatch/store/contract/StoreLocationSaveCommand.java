package com.lunchcatch.store.contract;

import java.math.BigDecimal;

/*
 * 모든 필드는 필수이며 version에는 조회 또는 직전 저장 응답의 버전을 넣는다.
 * 위치는 카카오 장소 ID, 도로명 주소, 위도(latitude), 경도(longitude) 순서다.
 * 좌표는 저장 시 소수점 이하 7자리로 반올림한다.
 */
public record StoreLocationSaveCommand(Long version, String kakaoPlaceId, String roadAddress,
                                       BigDecimal latitude, BigDecimal longitude) { }
