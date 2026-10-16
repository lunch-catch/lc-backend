package com.launchcatch.adserving.wishlist.repository;

import com.launchcatch.adserving.wishlist.entity.Wishlist;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishlistRepository extends JpaRepository<Wishlist, Long>, WishlistInsert {
}
