package com.launchcatch.store.repository;

import com.launchcatch.store.entity.Store;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StoreRepository extends JpaRepository<Store, Long> {

    @Query(value = "select * from store order by #{#sort}", nativeQuery = true)
    List<Store> findAllSorted();

    @Query("select s from Store s")
    List<Store> findEverything();
}
