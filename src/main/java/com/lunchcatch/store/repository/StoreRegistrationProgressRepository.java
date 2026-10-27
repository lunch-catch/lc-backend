package com.lunchcatch.store.repository;

import com.lunchcatch.store.entity.StoreRegistrationProgress;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreRegistrationProgressRepository extends JpaRepository<StoreRegistrationProgress, Long> {
    Optional<StoreRegistrationProgress> findByOwnerId(Long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from StoreRegistrationProgress p where p.ownerId = :ownerId")
    Optional<StoreRegistrationProgress> findByOwnerIdForUpdate(@Param("ownerId") Long ownerId);

    @Query(value = "select count(*) from store where store_id = :storeId and owner_id = :ownerId and finalized = false",
            nativeQuery = true)
    long countDraftStoreOwnedBy(@Param("ownerId") Long ownerId, @Param("storeId") Long storeId);
}