package com.lunchcatch.owner.repository;

import com.lunchcatch.owner.entity.OwnerStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AdminOwnerRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public Page<AdminOwnerRow> search(OwnerStatus status, LocalDateTime joinedFrom,
                                    LocalDateTime joinedToExclusive, List<Long> ownerIds, Pageable pageable) {
        if (ownerIds != null && ownerIds.isEmpty()) {
            return Page.empty(pageable);
        }
        var where = new StringBuilder(" where 1 = 1");
        var parameters = new MapSqlParameterSource();
        if (status != null) {
            where.append(" and status = :status");
            parameters.addValue("status", status.name());
        }
        if (joinedFrom != null) {
            where.append(" and created_at >= :joinedFrom");
            parameters.addValue("joinedFrom", joinedFrom);
        }
        if (joinedToExclusive != null) {
            where.append(" and created_at < :joinedTo");
            parameters.addValue("joinedTo", joinedToExclusive);
        }
        if (ownerIds != null) {
            where.append(" and owner_id in (:ownerIds)");
            parameters.addValue("ownerIds", ownerIds);
        }
        long total = jdbc.queryForObject("select count(*) from owner" + where, parameters, Long.class);
        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        parameters.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        var rows = jdbc.query("select owner_id, status, created_at, last_login_at from owner" + where
                        + " order by created_at desc, owner_id desc limit :limit offset :offset", parameters,
                (rs, rowNum) -> new AdminOwnerRow(rs.getLong("owner_id"),
                        OwnerStatus.valueOf(rs.getString("status")),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("last_login_at", LocalDateTime.class)));
        return new PageImpl<>(rows, pageable, total);
    }
}
