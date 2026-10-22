package com.launchcatch.store.repository;

import com.launchcatch.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long>, StoreRepositoryCustom {
    Optional<Store> findByOwnerId(Long ownerId);
}
