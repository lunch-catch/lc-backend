package com.launchcatch.owner.dto;

import java.time.LocalDateTime;

public record StoreCompleteResponse(Long storeId, boolean finalized, LocalDateTime finalizedAt,
                                    String ownerStatus, long version) { }
