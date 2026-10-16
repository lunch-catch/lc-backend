package com.launchcatch.store.service;

import com.launchcatch.store.config.OwnerTermsProperties;
import com.launchcatch.store.contract.OwnerTermsAgreementRecorder;
import com.launchcatch.store.contract.TermsAgreementCommand;
import com.launchcatch.store.contract.TermsAgreementResult;
import com.launchcatch.store.entity.StoreRegistrationStep;
import com.launchcatch.store.entity.OwnerTermsAgreement;
import com.launchcatch.store.exception.StoreErrorCode;
import com.launchcatch.store.exception.StoreException;
import com.launchcatch.store.repository.OwnerTermsAgreementRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final OwnerTermsProperties properties;
    private final StoreRegistrationProgressService progresses;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TermsAgreementResult recordAgreements(Long ownerId, long expectedVersion, List<TermsAgreementCommand> commands) {
        if (ownerId == null || ownerId <= 0 || expectedVersion < 0 || commands == null || commands.isEmpty() || commands.size() > 100) {
            throw new IllegalArgumentException("점주와 약관 동의 목록이 필요합니다.");
        }
        List<OwnerTermsProperties.Term> policies = properties.policies();
        if (policies.isEmpty()) {
            throw new StoreException(StoreErrorCode.TERMS_POLICY_UNAVAILABLE);
        }
        var progress = progresses.initialize(ownerId);
        if (progress.version() != expectedVersion) {
            throw new StoreException(StoreErrorCode.REGISTRATION_VERSION_CONFLICT);
        }
        Map<String, OwnerTermsProperties.Term> byType = new HashMap<>();
        for (var policy : policies) {
            byType.put(policy.termsType(), policy);
        }
        Set<String> submitted = new HashSet<>();
        List<OwnerTermsAgreement> pending = new ArrayList<>();
        for (var command : commands) {
            if (command == null) {
                throw new IllegalArgumentException("약관 항목은 null일 수 없습니다.");
            }
            var policy = byType.get(command.termsType());
            if (policy == null || !policy.termsVersion().equals(command.termsVersion())
                    || !submitted.add(command.termsType())) {
                throw new IllegalArgumentException("지원하지 않는 약관이거나 요청에 중복된 약관입니다.");
            }
            if (policy.required() && !command.agreed()) {
                throw new StoreException(StoreErrorCode.REQUIRED_TERMS_NOT_COMPLETED);
            }
            pending.add(OwnerTermsAgreement.record(ownerId, policy.termsType(), policy.termsVersion(),
                    policy.required(), command.agreed()));
        }
        List<OwnerTermsAgreement> stored = agreements.findAllByOwnerId(ownerId);
        // 기존의 유효한 동의와 이번 입력을 합쳐 현재 정책의 필수 동의를 확인한다.
        List<OwnerTermsAgreement> combined = new ArrayList<>(stored);
        combined.addAll(pending);
        if (!requiredTermsCompleted(policies, combined)) {
            throw new StoreException(StoreErrorCode.REQUIRED_TERMS_NOT_COMPLETED);
        }
        List<OwnerTermsAgreement> replacements = new ArrayList<>();
        for (var next : pending) {
            var previous = stored.stream().filter(row -> row.getTermsType().equals(next.getTermsType())
                    && row.getTermsVersion().equals(next.getTermsVersion())).findFirst();
            if (previous.isPresent()) {
                var row = previous.get();
                row.revise(next.isRequired(), next.isAgreed());
                replacements.add(row);
            } else {
                replacements.add(next);
            }
        }
        // 생략한 선택 약관은 미동의로 저장한다.
        for (var policy : policies) {
            if (!policy.required() && !submitted.contains(policy.termsType())) {
                var previous = stored.stream().filter(row -> row.getTermsType().equals(policy.termsType())
                        && row.getTermsVersion().equals(policy.termsVersion())).findFirst();
                if (previous.isPresent()) {
                    previous.get().revise(false, false);
                    replacements.add(previous.get());
                } else {
                    replacements.add(OwnerTermsAgreement.record(ownerId, policy.termsType(), policy.termsVersion(), false, false));
                }
            }
        }
        // 점주 행 잠금으로 같은 점주의 쓰기를 직렬화하고 기존 UNIQUE 제약을 최종 방어선으로 둔다.
        try {
            agreements.saveAllAndFlush(replacements);
        } catch (DataIntegrityViolationException e) {
            // UNIQUE 충돌만 중복 동의로 바꾸고 다른 무결성 오류는 숨기지 않는다.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && constraint.getConstraintName() != null
                        && (constraint.getConstraintName().equals("uk_owner_terms_version")
                        || constraint.getConstraintName().endsWith(".uk_owner_terms_version"))) {
                    throw new StoreException(StoreErrorCode.TERMS_ALREADY_RECORDED, e);
                }
            }
            throw e;
        }
        boolean completed = requiredTermsCompleted(policies, agreements.findAllByOwnerId(ownerId));
        if (!completed) {
            throw new StoreException(StoreErrorCode.REQUIRED_TERMS_NOT_COMPLETED);
        }
        var saved = progress.resumeStep() == StoreRegistrationStep.TERMS
                ? progresses.completeStep(ownerId, expectedVersion, StoreRegistrationStep.TERMS, null)
                : progresses.reviseStep(ownerId, expectedVersion, StoreRegistrationStep.TERMS);
        return new TermsAgreementResult(true, saved.version(), saved.resumeStep().name());
    }

    private boolean requiredTermsCompleted(List<OwnerTermsProperties.Term> policies,
            List<OwnerTermsAgreement> stored) {
        return policies.stream().filter(OwnerTermsProperties.Term::required).allMatch(policy ->
                stored.stream().anyMatch(agreement -> agreement.getTermsType().equals(policy.termsType())
                        && agreement.getTermsVersion().equals(policy.termsVersion())
                        && agreement.isAgreed() && agreement.getWithdrawnAt() == null));
    }
}