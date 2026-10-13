package com.launchcatch.billing.repository;

import static com.launchcatch.billing.entity.QPayment.payment;
import static com.launchcatch.billing.entity.QPaymentReconciliationOutbox.paymentReconciliationOutbox;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/*
 * 조건이 동적으로 붙고 떨어지는 조회라 QueryDSL 로 쓴다.
 * Spring Data 가 PaymentRepository 의 구현 조각으로 이 클래스를 이름(~Impl)으로 찾아 붙인다.
 * 아웃박스는 결제당 한 행(UNIQUE)이라 left join 해도 결제 행이 늘지 않는다.
 */
@RequiredArgsConstructor
class PaymentRepositoryImpl implements PaymentRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<AdminPaymentRow> findAdminPayments(AdminPaymentCondition condition) {
        return adminRows()
                .where(filters(condition))
                .orderBy(order(condition))
                .offset(condition.offset())
                .limit(condition.limit())
                .fetch();
    }

    @Override
    public Optional<AdminPaymentRow> findAdminPaymentById(Long paymentId) {
        return Optional.ofNullable(adminRows().where(payment.id.eq(paymentId)).fetchOne());
    }

    private JPAQuery<AdminPaymentRow> adminRows() {
        return queryFactory
                .select(Projections.constructor(AdminPaymentRow.class,
                        payment.id, payment.orderId, payment.pgTid, payment.ownerId, payment.amount,
                        payment.status, payment.failureReason, payment.createdAt, payment.approvedAt,
                        payment.canceledAt, paymentReconciliationOutbox.status,
                        paymentReconciliationOutbox.attemptCount, paymentReconciliationOutbox.maxRetry,
                        paymentReconciliationOutbox.nextRetryAt, paymentReconciliationOutbox.lastError))
                .from(payment)
                .leftJoin(paymentReconciliationOutbox).on(paymentReconciliationOutbox.paymentId.eq(payment.id));
    }

    @Override
    public long countAdminPayments(AdminPaymentCondition condition) {
        Long count = queryFactory
                .select(payment.count())
                .from(payment)
                .where(filters(condition))
                .fetchOne();
        return count == null ? 0L : count;
    }

    private BooleanExpression[] filters(AdminPaymentCondition condition) {
        return new BooleanExpression[]{
                condition.ownerId() == null ? null : payment.ownerId.eq(condition.ownerId()),
                condition.orderId() == null ? null : payment.orderId.eq(condition.orderId()),
                condition.pgTid() == null ? null : payment.pgTid.eq(condition.pgTid()),
                condition.status() == null ? null : payment.status.eq(condition.status()),
                condition.fromInclusive() == null ? null : payment.createdAt.goe(condition.fromInclusive()),
                condition.toExclusive() == null ? null : payment.createdAt.lt(condition.toExclusive())
        };
    }

    private OrderSpecifier<?>[] order(AdminPaymentCondition condition) {
        Order direction = condition.ascending() ? Order.ASC : Order.DESC;
        OrderSpecifier<?> primary = switch (condition.sort()) {
            case REQUESTED_AT -> new OrderSpecifier<>(direction, payment.createdAt);
            case AMOUNT -> new OrderSpecifier<>(direction, payment.amount);
            case APPROVED_AT -> new OrderSpecifier<>(direction, payment.approvedAt).nullsLast();
        };
        return new OrderSpecifier<?>[]{primary, new OrderSpecifier<>(direction, payment.id)};
    }
}
