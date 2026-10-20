package com.launchcatch.store.repository;

import com.launchcatch.store.entity.StoreImage;
import com.launchcatch.store.entity.StoreImageType;
import com.launchcatch.store.entity.StoreImageUploadStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreImageRepository extends JpaRepository<StoreImage, Long> {

    Optional<StoreImage> findByUploadId(String uploadId);

    // 로고를 다시 올릴 때 덮어쓸 기존 행을 찾는다. 로고는 가게당 1개(uk_store_image_single_logo)라 최대 한 행이다.
    Optional<StoreImage> findByStoreIdAndImageType(Long storeId, StoreImageType imageType);

    // IMAGES 단계 완료에서 "업로드를 마친 로고가 있는지" 볼 때 쓴다. PENDING 로고는 아직 없는 것으로 본다.
    boolean existsByStoreIdAndImageTypeAndUploadStatus(
            Long storeId, StoreImageType imageType, StoreImageUploadStatus uploadStatus);

    // 다음 인테리어 sort_order를 정할 때와 0~3개 제한을 볼 때 쓴다.
    long countByStoreIdAndImageType(Long storeId, StoreImageType imageType);
}