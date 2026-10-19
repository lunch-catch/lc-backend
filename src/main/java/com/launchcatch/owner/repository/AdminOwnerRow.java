package com.launchcatch.owner.repository;

import com.launchcatch.owner.entity.OwnerStatus;
import java.time.LocalDateTime;

public record AdminOwnerRow(long ownerId, OwnerStatus status, LocalDateTime joinedAt,
                            LocalDateTime lastLoginAt) { }
