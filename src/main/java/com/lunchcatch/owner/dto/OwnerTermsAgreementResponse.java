package com.lunchcatch.owner.dto;

public record OwnerTermsAgreementResponse(boolean requiredTermsCompleted, long version, String resumeStep) { }
