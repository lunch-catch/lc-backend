package com.lunchcatch.campaign.poster;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.web.util.UriUtils;

/*
 * store.StoreImageUrlResolver(#375)와 같은 모양이다. campaign은 store 내부 클래스를 가져다
 * 쓸 수 없어(contract만 참조 가능, 런치캐치_도메인_구조와_의존성_설계.md 1.3) 작게 새로 둔다.
 */
public final class PosterImageUrlResolver {
    private final String baseUrl;

    public PosterImageUrlResolver(String baseUrl) {
        String stripped = baseUrl.strip();
        int end = stripped.length();
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
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("이미지 URL을 생성하려면 cdn.base-url 설정이 필요합니다.");
        }
        return baseUrl + "/" + UriUtils.encodePath(objectKey, StandardCharsets.UTF_8);
    }
}
