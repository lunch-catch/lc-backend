package com.lunchcatch.admin.service;

import com.lunchcatch.admin.contract.AdminSummary;
import com.lunchcatch.admin.contract.AdminSummaryQuery;
import com.lunchcatch.admin.dto.AdminIdSummary;
import com.lunchcatch.admin.repository.AdminRepository;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminSummaryService implements AdminSummaryQuery {

    private final AdminRepository adminRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AdminSummary> findSummariesByIds(Collection<Long> adminIds) {
        // Map.of() 는 get(null) 이 예외라서, 호출하는 쪽이 null ID 로 get 해도 안전한 빈 맵을 돌려준다.
        if (adminIds == null) {
            return Collections.emptyMap();
        }
        List<Long> ids = adminIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return adminRepository.findSummariesByIdIn(ids).stream()
                .collect(Collectors.toMap(
                        AdminIdSummary::id,
                        row -> new AdminSummary(row.name(), row.loginId()),
                        (first, second) -> first));
    }
}
