package com.lunchcatch.coupon.repository;

import static com.lunchcatch.coupon.entity.QMemberCoupon.memberCoupon;

import com.lunchcatch.coupon.CouponListFilter;
import com.lunchcatch.coupon.entity.MemberCoupon;
import com.lunchcatch.coupon.entity.MemberCouponStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class MemberCouponRepositoryImpl implements MemberCouponRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<MemberCoupon> findPage(Long memberId, CouponListFilter filter, LocalDateTime now,
            LocalDateTime cursorIssueTime, Long cursorId, int limit) {
        BooleanBuilder conditions = new BooleanBuilder(memberCoupon.memberId.eq(memberId))
                .and(memberCoupon.status.ne(MemberCouponStatus.REMOVED));

        // 저장 상태가 아직 ISSUED여도 사용 종료 시각에 도달했으면 만료 필터에 포함한다.
        switch (filter) {
            case ALL -> { }
            case USABLE -> conditions.and(memberCoupon.status.eq(MemberCouponStatus.ISSUED))
                    .and(memberCoupon.usableStartAt.loe(now)).and(memberCoupon.usableEndAt.gt(now));
            case ISSUED -> conditions.and(memberCoupon.status.eq(MemberCouponStatus.ISSUED))
                    .and(memberCoupon.usableEndAt.gt(now));
            case USED -> conditions.and(memberCoupon.status.eq(MemberCouponStatus.USED));
            case EXPIRED -> conditions.and(memberCoupon.status.eq(MemberCouponStatus.EXPIRED)
                    .or(memberCoupon.status.eq(MemberCouponStatus.ISSUED).and(memberCoupon.usableEndAt.loe(now))));
        }

        // 발급 시각이 같으면 ID로 순서를 구분하여 직전 페이지의 마지막 항목 다음부터 조회한다.
        if (cursorIssueTime != null) {
            conditions.and(memberCoupon.createdAt.lt(cursorIssueTime)
                    .or(memberCoupon.createdAt.eq(cursorIssueTime).and(memberCoupon.id.lt(cursorId))));
        }

        return queryFactory.selectFrom(memberCoupon)
                .where(conditions)
                .orderBy(memberCoupon.createdAt.desc(), memberCoupon.id.desc())
                .limit(limit)
                // 목록 조회의 만료 처리도 단건 조회와 삭제와 동일한 행 잠금을 사용한다.
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetch();
    }
}
