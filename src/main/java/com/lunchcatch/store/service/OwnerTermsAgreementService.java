package com.lunchcatch.store.service;

import com.lunchcatch.store.contract.OwnerTermsAgreementRecorder;
import com.lunchcatch.store.contract.TermsAgreementResult;
import com.lunchcatch.store.entity.OwnerTermsAgreement;
import com.lunchcatch.store.entity.StoreRegistrationStep;
import com.lunchcatch.store.exception.StoreErrorCode;
import com.lunchcatch.store.exception.StoreException;
import com.lunchcatch.store.repository.OwnerTermsAgreementRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OwnerTermsAgreementService implements OwnerTermsAgreementRecorder {
    private final OwnerTermsAgreementRepository agreements;
    private final StoreRegistrationProgressService progresses;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TermsAgreementResult recordAgreements(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("유효한 점주 ID가 필요합니다.");
        }
        var progress = progresses.initialize(ownerId);
        if (agreements.findByOwnerId(ownerId).isPresent()) {
            throw new StoreException(StoreErrorCode.TERMS_ALREADY_RECORDED);
        }
        if (progress.resumeStep() != StoreRegistrationStep.TERMS) {
            throw new StoreException(StoreErrorCode.REGISTRATION_STEP_CONFLICT);
        }
        var agreement = OwnerTermsAgreement.record(ownerId, LocalDateTime.now(clock));
        try {
            agreements.saveAndFlush(agreement);
        } catch (DataIntegrityViolationException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && constraint.getConstraintName() != null
                        && (constraint.getConstraintName().equals("uk_owner_terms_agreement_owner")
                        || constraint.getConstraintName().endsWith(".uk_owner_terms_agreement_owner"))) {
                    throw new StoreException(StoreErrorCode.TERMS_ALREADY_RECORDED, e);
                }
            }
            throw e;
        }
        var saved = progresses.completeStep(ownerId, progress.version(), StoreRegistrationStep.TERMS, null);
        return new TermsAgreementResult(true, saved.version(), saved.resumeStep().name());
    }
}