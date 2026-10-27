package com.lunchcatch.owner.dto;

import java.time.OffsetDateTime;

public record StoreCompleteResponse(Long storeId, boolean finalized, OffsetDateTime finalizedAt,
                                    String ownerStatus, long version) { }
