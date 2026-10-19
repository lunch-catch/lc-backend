package com.launchcatch.store.contract;

// 현재 진행 버전과 필수 입력 5개(상호명·업종 코드·대표자 성명·사업자등록번호·전화번호)를 전달한다.
public record StoreInfoSaveCommand(Long version, String name, String categoryCode, String representativeName,
                                   String businessRegistrationNumber, String phone) { }
