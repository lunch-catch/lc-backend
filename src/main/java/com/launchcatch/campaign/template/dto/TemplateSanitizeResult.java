package com.launchcatch.campaign.template.dto;

import java.util.List;

public record TemplateSanitizeResult(String html, List<String> removedElements) {
}
