package com.launchcatch.store.repository;

import com.launchcatch.store.entity.StoreMenuImage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreMenuImageRepository extends JpaRepository<StoreMenuImage, Long> {

    Optional<StoreMenuImage> findByUploadId(String uploadId);

    // 재업로드 요청에서 그 메뉴에 이미지가 이미 있는지, 포기한 PENDING 행이 있는지 본다.
    Optional<StoreMenuImage> findByStoreMenuId(Long storeMenuId);
}