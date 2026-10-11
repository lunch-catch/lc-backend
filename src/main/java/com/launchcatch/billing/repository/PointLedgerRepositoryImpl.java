package com.launchcatch.billing.repository;

import static com.launchcatch.billing.entity.QPointLedger.pointLedger;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;

/*
 * 조건이 동적으로 붙고 떨어지는 조회라 QueryDSL 로 쓴다.
 * Spring Data 가 PointLedgerRepository 의 구현 조각으로 이 클래스를 이름(~Impl)으로 찾아 붙인다.
 */
@RequiredArgsConstructor
class PointLedgerRepositoryImpl implements PointLedgerRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<LedgerRow> findOwnerLedger(OwnerLedgerCondition condition) {
        return queryFactory
                .select(Projections.constructor(LedgerRow.class,
                        pointLedger.id, pointLedger.entryType, pointLedger.amount, pointLedger.campaignId,
                        pointLedger.businessDate, pointLedger.paymentId, pointLedger.createdAt))
                .from(pointLedger)
                .where(
                        pointLedger.ownerId.eq(condition.ownerId()),
                        pointLedger.entryType.in(condition.types()),
                        createdAtFrom(condition),
                        createdAtBefore(condition),
                        campaignIs(condition),
                        afterCursor(condition))
                .orderBy(pointLedger.createdAt.desc(), pointLedger.id.desc())
                .limit(condition.limit())
                .fetch();
    }

    private BooleanExpression createdAtFrom(OwnerLedgerCondition condition) {
        return condition.fromInclusive() == null ? null : pointLedger.createdAt.goe(condition.fromInclusive());
    }

    private BooleanExpression createdAtBefore(OwnerLedgerCondition condition) {
        return condition.toExclusive() == null ? null : pointLedger.createdAt.lt(condition.toExclusive());
    }

    private BooleanExpression campaignIs(OwnerLedgerCondition condition) {
        return condition.campaignId() == null ? null : pointLedger.campaignId.eq(condition.campaignId());
    }

    // 직전 페이지의 마지막 행보다 뒤에 있는 행만 남긴다. 기록 시각이 같으면 ID 로 가른다
    private BooleanExpression afterCursor(OwnerLedgerCondition condition) {
        if (condition.cursorCreatedAt() == null || condition.cursorId() == null) {
            return null;
        }
        return pointLedger.createdAt.lt(condition.cursorCreatedAt())
                .or(pointLedger.createdAt.eq(condition.cursorCreatedAt())
                        .and(pointLedger.id.lt(condition.cursorId())));
    }
}
