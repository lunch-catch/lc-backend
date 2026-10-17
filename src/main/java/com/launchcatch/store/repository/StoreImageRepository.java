package com.launchcatch.store.repository;

import com.launchcatch.store.entity.StoreImage;
import com.launchcatch.store.entity.StoreImageType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreImageRepository extends JpaRepository<StoreImage, Long> {

    Optional<StoreImage> findByUploadId(String uploadId);

    boolean existsByStoreIdAndImageType(Long storeId, StoreImageType imageType);

    // 다음 인테리어 sort_order를 정할 때와 0~3개 제한을 볼 때 쓴다.
    long countByStoreIdAndImageType(Long storeId, StoreImageType imageType);
}
