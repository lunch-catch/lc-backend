package com.launchcatch.store.contract;

public record TermsAgreementCommand(String termsType, String termsVersion, boolean agreed) { }