package com.launchcatch.owner.dto;

public record OwnerTermsAgreementResponse(boolean requiredTermsCompleted, long version, String resumeStep) { }
