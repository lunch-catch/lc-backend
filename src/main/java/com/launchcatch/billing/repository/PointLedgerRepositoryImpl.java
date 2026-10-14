package com.launchcatch.billing.repository;

import static com.launchcatch.billing.entity.QPayment.payment;
import static com.launchcatch.billing.entity.QPointLedger.pointLedger;

import com.launchcatch.billing.dto.BalanceStep;
import com.launchcatch.billing.entity.EntryType;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    @Override
    public List<ReserveSumRow> sumReservedByBusinessDate(Long ownerId, LocalDate from, LocalDate to, Long campaignId) {
        return queryFactory
                .select(Projections.constructor(ReserveSumRow.class,
                        pointLedger.businessDate, pointLedger.campaignId, pointLedger.amount.sum()))
                .from(pointLedger)
                .where(
                        pointLedger.ownerId.eq(ownerId),
                        pointLedger.entryType.eq(EntryType.RESERVE),
                        pointLedger.businessDate.between(from, to),
                        campaignIdIs(campaignId))
                .groupBy(pointLedger.businessDate, pointLedger.campaignId)
                .fetch();
    }

    @Override
    public List<DeductSumRow> sumDeductedByCampaign(Long ownerId, LocalDateTime from, LocalDateTime to,
                                                    Long campaignId) {
        return queryFactory
                .select(Projections.constructor(DeductSumRow.class,
                        pointLedger.campaignId, pointLedger.amount.sum(), pointLedger.count()))
                .from(pointLedger)
                .where(
                        pointLedger.ownerId.eq(ownerId),
                        pointLedger.entryType.eq(EntryType.DEDUCT),
                        pointLedger.createdAt.goe(from),
                        pointLedger.createdAt.lt(to),
                        campaignIdIs(campaignId))
                .groupBy(pointLedger.campaignId)
                .fetch();
    }

    private BooleanExpression campaignIdIs(Long campaignId) {
        return campaignId == null ? null : pointLedger.campaignId.eq(campaignId);
    }

    @Override
    public List<AdminLedgerRow> findAdminLedger(AdminLedgerCondition condition) {
        OrderSpecifier<?>[] order = condition.ascending()
                ? new OrderSpecifier<?>[]{pointLedger.createdAt.asc(), pointLedger.id.asc()}
                : new OrderSpecifier<?>[]{pointLedger.createdAt.desc(), pointLedger.id.desc()};
        return queryFactory
                .select(Projections.constructor(AdminLedgerRow.class,
                        pointLedger.id, pointLedger.ownerId, pointLedger.entryType, pointLedger.amount,
                        pointLedger.campaignId, pointLedger.serveId, payment.pgTid, pointLedger.source,
                        pointLedger.reason, pointLedger.createdAt))
                .from(pointLedger)
                .leftJoin(payment).on(payment.id.eq(pointLedger.paymentId))
                .where(adminFilters(condition))
                .orderBy(order)
                .limit(condition.limit())
                .fetch();
    }

    @Override
    public List<BalanceStep> findBalanceSteps(Long ownerId, LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return queryFactory
                .select(Projections.constructor(BalanceStep.class,
                        pointLedger.id, pointLedger.createdAt, pointLedger.amount))
                .from(pointLedger)
                .where(
                        pointLedger.ownerId.eq(ownerId),
                        pointLedger.entryType.ne(EntryType.DEDUCT),
                        pointLedger.createdAt.goe(fromInclusive),
                        pointLedger.createdAt.lt(toExclusive))
                .orderBy(pointLedger.createdAt.asc(), pointLedger.id.asc())
                .fetch();
    }

    @Override
    public List<FlowRow> findFlowRows(Long ownerId, LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return queryFactory
                .select(Projections.constructor(FlowRow.class,
                        pointLedger.entryType, pointLedger.createdAt, pointLedger.amount))
                .from(pointLedger)
                .where(
                        pointLedger.ownerId.eq(ownerId),
                        pointLedger.entryType.in(EntryType.CHARGE, EntryType.REFUND, EntryType.ADJUST),
                        pointLedger.createdAt.goe(fromInclusive),
                        pointLedger.createdAt.lt(toExclusive))
                .orderBy(pointLedger.createdAt.asc(), pointLedger.id.asc())
                .fetch();
    }

    @Override
    public List<BusinessDateSumRow> sumReserveAndReleaseByBusinessDate(Long ownerId, LocalDate from, LocalDate to) {
        return queryFactory
                .select(Projections.constructor(BusinessDateSumRow.class,
                        pointLedger.businessDate, pointLedger.entryType, pointLedger.amount.sum()))
                .from(pointLedger)
                .where(
                        pointLedger.ownerId.eq(ownerId),
                        pointLedger.entryType.in(EntryType.RESERVE, EntryType.RELEASE),
                        pointLedger.businessDate.between(from, to))
                .groupBy(pointLedger.businessDate, pointLedger.entryType)
                .fetch();
    }

    private BooleanExpression[] adminFilters(AdminLedgerCondition condition) {
        return new BooleanExpression[]{
                pointLedger.ownerId.eq(condition.ownerId()),
                condition.type() == null ? null : pointLedger.entryType.eq(condition.type()),
                pointLedger.createdAt.goe(condition.fromInclusive()),
                pointLedger.createdAt.lt(condition.toExclusive()),
                campaignIdIs(condition.campaignId()),
                afterCursor(condition)
        };
    }

    private BooleanExpression afterCursor(AdminLedgerCondition condition) {
        if (condition.cursorCreatedAt() == null || condition.cursorId() == null) {
            return null;
        }
        BooleanExpression byTime = condition.ascending()
                ? pointLedger.createdAt.gt(condition.cursorCreatedAt())
                : pointLedger.createdAt.lt(condition.cursorCreatedAt());
        BooleanExpression byId = condition.ascending()
                ? pointLedger.id.gt(condition.cursorId())
                : pointLedger.id.lt(condition.cursorId());
        return byTime.or(pointLedger.createdAt.eq(condition.cursorCreatedAt()).and(byId));
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
