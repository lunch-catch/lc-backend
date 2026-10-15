package com.launchcatch.store;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

// DB에 저장한 객체 키를 기존 CDN 주소와 연결하며 객체 스토리지에 별도 요청을 보내지 않는다.
public final class StoreImageUrlResolver {
    private final String baseUrl;

    public StoreImageUrlResolver(String baseUrl) {
        String stripped = baseUrl.strip();
        int end = stripped.length();
        // 주소 끝의 /만 뒤에서 한 번씩 확인해 제거한다.
        while (end > 0 && stripped.charAt(end - 1) == '/') {
            end--;
        }
        this.baseUrl = stripped.substring(0, end);
        if (!this.baseUrl.isEmpty()) {
            URI uri = URI.create(this.baseUrl);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("cdn.base-url은 쿼리와 프래그먼트 없는 HTTP(S) 주소여야 합니다.");
            }
        }
    }

    public String resolve(String objectKey) {
        if (objectKey == null) {
            return null;
        }
        // 이미지가 있는데 CDN 설정이 빠진 경우는 이미지 누락이 아니므로 오류를 호출자에게 전달한다.
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("이미지 URL을 생성하려면 cdn.base-url 설정이 필요합니다.");
        }
        // 폴더 구분자인 /는 유지하고 공백·한글·# 같은 문자가 URL 의미를 바꾸지 않도록 인코딩한다.
        return baseUrl + "/" + UriUtils.encodePath(objectKey, StandardCharsets.UTF_8);
    }
}