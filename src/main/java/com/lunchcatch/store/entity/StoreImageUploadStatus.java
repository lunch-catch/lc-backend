package com.lunchcatch.store.entity;

/*
 * store_image.upload_status 열의 값이다. DB CHECK 제약은 PENDING/UPLOADED/VERIFIED 셋을
 * 허용하지만, 이 구현은 PENDING -> VERIFIED만 쓴다(#375, 단순하게 가는 쪽으로 결정).
 */
public enum StoreImageUploadStatus {
    PENDING,
    VERIFIED
}
