package com.launchcatch.store.repository;

import com.launchcatch.store.entity.StoreMenuImage;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreMenuImageRepository extends JpaRepository<StoreMenuImage, Long> {

    Optional<StoreMenuImage> findByUploadId(String uploadId);
}
