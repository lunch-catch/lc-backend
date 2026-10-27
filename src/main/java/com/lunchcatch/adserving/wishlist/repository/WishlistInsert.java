package com.lunchcatch.adserving.wishlist.repository;

import com.lunchcatch.adserving.wishlist.entity.Wishlist;

public interface WishlistInsert {

    // 같은 (사용자, 캠페인)이 이미 있으면 넣지 않는다. 처음 넣었으면 true 다.
    boolean insertIgnore(Wishlist wishlist);
}
