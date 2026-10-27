package com.lunchcatch.store.contract;

public record TermsAgreementResult(boolean requiredTermsCompleted, long version, String resumeStep) { }
