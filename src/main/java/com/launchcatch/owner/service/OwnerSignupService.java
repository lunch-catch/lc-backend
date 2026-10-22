package com.launchcatch.owner.service;

import com.launchcatch.global.exception.ConstraintViolations;
import com.launchcatch.owner.dto.OwnerSignupRequest;
import com.launchcatch.owner.dto.OwnerSignupResponse;
import com.launchcatch.owner.entity.Owner;
import com.launchcatch.owner.exception.OwnerErrorCode;
import com.launchcatch.owner.exception.OwnerException;
import com.launchcatch.owner.repository.OwnerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OwnerSignupService {
    private final OwnerRepository ownerRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public OwnerSignupService(OwnerRepository ownerRepository,
                              PasswordEncoder passwordEncoder,
                              PlatformTransactionManager transactionManager) {
        this.ownerRepository = ownerRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OwnerSignupResponse signup(OwnerSignupRequest request) {
        if (ownerRepository.existsByEmail(request.email())) {
            throw new OwnerException(OwnerErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // BCrypt 연산 중 DB 트랜잭션을 점유하지 않는다.
        String passwordHash = passwordEncoder.encode(request.password());
        Owner owner = Owner.create(request.email(), passwordHash);

        try {
            transactionTemplate.executeWithoutResult(status -> ownerRepository.saveAndFlush(owner));
        } catch (DataIntegrityViolationException e) {
            // 사전 중복 조회를 동시에 통과한 요청도 DB UNIQUE 제약으로 막는다.
            if (ConstraintViolations.isConstraintViolation(e, "uk_owner_email")) {
                throw new OwnerException(OwnerErrorCode.EMAIL_ALREADY_EXISTS);
            }
            throw e;
        }

        return new OwnerSignupResponse(owner.getEmail(), owner.getRole(), owner.getStatus());
    }
}