package com.launchcatch.store.repository;

import com.launchcatch.store.entity.OwnerTermsAgreement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerTermsAgreementRepository extends JpaRepository<OwnerTermsAgreement, Long> {
    List<OwnerTermsAgreement> findAllByOwnerId(Long ownerId);
}