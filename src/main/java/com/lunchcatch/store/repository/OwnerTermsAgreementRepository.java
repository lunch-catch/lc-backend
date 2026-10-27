package com.lunchcatch.store.repository;

import com.lunchcatch.store.entity.OwnerTermsAgreement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerTermsAgreementRepository extends JpaRepository<OwnerTermsAgreement, Long> {
    Optional<OwnerTermsAgreement> findByOwnerId(Long ownerId);
}