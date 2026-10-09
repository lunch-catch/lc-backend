package com.launchcatch.member.repository;

import static com.launchcatch.member.entity.QMember.member;

import com.launchcatch.global.query.LikePatternEscaper;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/*
 * 조건이 동적으로 붙고 떨어지는 조회라 JPQL 문자열 대신 QueryDSL 로 쓴다.
 * Spring Data 가 MemberRepository 의 구현 조각으로 이 클래스를 이름(~Impl)으로 찾아 붙인다.
 */
@RequiredArgsConstructor
class MemberRepositoryImpl implements MemberRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<MemberAdminRow> searchForAdmin(
            MemberSearchCondition condition, MemberSortKey sortKey, Sort.Direction direction, Pageable pageable) {
        BooleanExpression[] filters = {
                statusIs(condition),
                createdAtFrom(condition),
                createdAtBefore(condition),
                memberIdIs(condition),
                nicknameStartsWith(condition)
        };

        List<MemberAdminRow> rows = queryFactory
                .select(Projections.constructor(MemberAdminRow.class,
                        member.id, member.nickname, member.status, member.createdAt, member.lastLoginAt))
                .from(member)
                .where(filters)
                .orderBy(orderSpecifiers(sortKey, direction))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(member.count())
                .from(member)
                .where(filters)
                .fetchOne();

        return new PageImpl<>(rows, pageable, total == null ? 0L : total);
    }

    /*
     * 정렬 기준 뒤에 회원 번호를 같은 방향으로 붙여 순서를 하나로 정한다. MEMBER_ID 는 이 꼬리표가 곧 기준이다.
     * 마지막 로그인 시각이 null 인 행은 방향과 관계없이 맨 뒤에 둔다. MySQL 에는 NULLS LAST 가 없어 CASE 식을 앞 정렬 키로 둔다.
     */
    private OrderSpecifier<?>[] orderSpecifiers(MemberSortKey sortKey, Sort.Direction direction) {
        Order order = direction.isAscending() ? Order.ASC : Order.DESC;
        List<OrderSpecifier<?>> specifiers = new ArrayList<>();
        switch (sortKey) {
            case JOINED_AT -> specifiers.add(new OrderSpecifier<>(order, member.createdAt));
            case LAST_LOGIN_AT -> {
                specifiers.add(new OrderSpecifier<>(Order.ASC,
                        new CaseBuilder().when(member.lastLoginAt.isNull()).then(1).otherwise(0)));
                specifiers.add(new OrderSpecifier<>(order, member.lastLoginAt));
            }
            case NICKNAME -> specifiers.add(new OrderSpecifier<>(order, member.nickname));
            case MEMBER_ID -> {
            }
        }
        specifiers.add(new OrderSpecifier<>(order, member.id));
        return specifiers.toArray(new OrderSpecifier<?>[0]);
    }

    private BooleanExpression statusIs(MemberSearchCondition condition) {
        return condition.status() == null ? null : member.status.eq(condition.status());
    }

    private BooleanExpression createdAtFrom(MemberSearchCondition condition) {
        return condition.createdFrom() == null ? null : member.createdAt.goe(condition.createdFrom());
    }

    private BooleanExpression createdAtBefore(MemberSearchCondition condition) {
        return condition.createdBefore() == null ? null : member.createdAt.lt(condition.createdBefore());
    }

    private BooleanExpression memberIdIs(MemberSearchCondition condition) {
        return condition.memberId() == null ? null : member.id.eq(condition.memberId());
    }

    // TODO: 완전 부분 검색 구현을 위해 Full-Text, ngram, 검색 엔진 등 대체 방법을 검토한다.
    private BooleanExpression nicknameStartsWith(MemberSearchCondition condition) {
        if (condition.nicknamePrefix() == null) {
            return null;
        }
        String pattern = LikePatternEscaper.escapeLiteral(condition.nicknamePrefix()) + "%";
        return member.nickname.like(pattern, LikePatternEscaper.ESCAPE_CHARACTER);
    }
}
