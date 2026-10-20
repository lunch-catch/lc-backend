package com.launchcatch.store.dto;

// sortOrder 는 인테리어 이미지일 때만 값이 있고 로고면 null 이다.
public record StoreImagePrepareResult(String uploadId, String uploadUrl, Integer sortOrder) { }
