package com.launchcatch.adserving.wishlist.service;

import com.launchcatch.adserving.wishlist.dto.WishlistItemResponse;

// created 는 이번 요청이 찜을 처음 넣었는지다. 컨트롤러가 201 과 200 을 가른다.
public record WishResult(boolean created, WishlistItemResponse item) {
}
