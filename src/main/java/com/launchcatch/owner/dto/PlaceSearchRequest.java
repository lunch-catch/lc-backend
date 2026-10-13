package com.launchcatch.owner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlaceSearchRequest(@NotBlank @Size(max = 100) String query) { }
