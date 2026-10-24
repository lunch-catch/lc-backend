package com.launchcatch.admin.service;

import com.launchcatch.admin.contract.AdminNameQuery;
import com.launchcatch.admin.dto.AdminIdName;
import com.launchcatch.admin.repository.AdminRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminNameService implements AdminNameQuery {

    private final AdminRepository adminRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> findNamesByIds(Collection<Long> adminIds) {
        if (adminIds == null) {
            return Map.of();
        }
        List<Long> ids = adminIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return adminRepository.findNamesByIdIn(ids).stream()
                .collect(Collectors.toMap(AdminIdName::id, AdminIdName::name, (first, second) -> first));
    }
}
