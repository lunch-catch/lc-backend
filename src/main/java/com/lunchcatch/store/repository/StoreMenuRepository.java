package com.lunchcatch.store.repository;

import com.lunchcatch.store.entity.StoreMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreMenuRepository extends JpaRepository<StoreMenu, Long> {

    // 다음 메뉴 sort_order를 정할 때와 0~3개 제한을 볼 때 쓴다.
    long countByStoreId(Long storeId);

    boolean existsByIdAndStoreId(Long storeMenuId, Long storeId);

    // MENU 단계 완료 시 VERIFIED 이미지가 없는 메뉴가 남아 있는지 본다.
    @Query("select count(m) from StoreMenu m where m.storeId = :storeId "
            + "and not exists (select 1 from StoreMenuImage i "
            + "where i.storeMenuId = m.id and i.uploadStatus = com.lunchcatch.store.entity.StoreImageUploadStatus.VERIFIED)")
    long countWithoutVerifiedImage(@Param("storeId") Long storeId);
}
