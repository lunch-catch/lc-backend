package com.launchcatch.member.repository;

import static com.launchcatch.member.entity.QMember.member;

import com.launchcatch.global.query.LikePatternEscaper;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/*
 * 조건이 동적으로 붙고 떨어지는 조회라 JPQL 문자열 대신 QueryDSL 로 쓴다.
 * Spring Data 가 MemberRepository 의 구현 조각으로 이 클래스를 이름(~Impl)으로 찾아 붙인다.
 */
@RequiredArgsConstructor
class MemberRepositoryImpl implements MemberRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<MemberAdminRow> searchForAdmin(MemberSearchCondition condition, Pageable pageable) {
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
                .orderBy(member.createdAt.desc(), member.id.desc())
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
