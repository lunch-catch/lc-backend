package com.launchcatch.campaign.template.client;

public class TemplateGenerationTimeoutException extends RuntimeException {

    public TemplateGenerationTimeoutException(String message) {
        super(message);
    }

    public TemplateGenerationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
