package com.launchcatch.store.repository;

import com.launchcatch.store.entity.OwnerTermsAgreement;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerTermsAgreementRepository extends JpaRepository<OwnerTermsAgreement, Long> {
    Optional<OwnerTermsAgreement> findByOwnerId(Long ownerId);
}