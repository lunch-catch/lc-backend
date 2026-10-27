package com.lunchcatch.owner.repository;

import com.lunchcatch.owner.entity.OwnerStatus;
import java.time.LocalDateTime;

public record AdminOwnerRow(long ownerId, OwnerStatus status, LocalDateTime joinedAt,
                            LocalDateTime lastLoginAt) { }
