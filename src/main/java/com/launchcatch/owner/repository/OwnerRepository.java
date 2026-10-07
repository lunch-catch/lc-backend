package com.launchcatch.owner.repository;

import com.launchcatch.owner.entity.Owner;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OwnerRepository extends JpaRepository<Owner, Long> {
    boolean existsByEmail(String email);

    Optional<Owner> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Owner o where o.id = :id")
    Optional<Owner> findByIdForLogin(@Param("id") Long id);
}