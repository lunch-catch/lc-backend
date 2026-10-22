package com.launchcatch.store.repository;

import static com.launchcatch.store.entity.QStore.store;
import static com.launchcatch.store.entity.QStoreCampaignSummary.storeCampaignSummary;
import static com.launchcatch.store.entity.QStoreImage.storeImage;
import static com.launchcatch.store.entity.QStoreMenu.storeMenu;

import com.launchcatch.global.response.PageCursor;
import com.launchcatch.store.contract.StoreCampaignStatus;
import com.launchcatch.store.dto.StoreListRow;
import com.launchcatch.store.entity.StoreImageType;
import com.launchcatch.store.entity.StoreImageUploadStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class StoreRepositoryImpl implements StoreRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    @Override
    public List<StoreListRow> findPage(String query, BigDecimal longitude, BigDecimal latitude,
            List<String> categories, PageCursor cursor, LocalDate businessDate, int limit) {
        NumberExpression<Double> distance = distanceFrom(longitude, latitude);
        BooleanBuilder conditions = new BooleanBuilder(store.finalized.isTrue());
        // Service가 ALL을 빈 목록으로 전달하므로 카테고리가 있을 때만 IN 조건을 붙인다.
        if (!categories.isEmpty()) {
            conditions.and(store.categoryCode.in(categories));
        }
        if (query != null) {
            // 메뉴를 직접 조인하면 가게가 중복될 수 있으므로 EXISTS로 일치하는 메뉴의 존재 여부만 검사한다.
            conditions.and(store.name.contains(query).or(JPAExpressions.selectOne().from(storeMenu)
                    .where(storeMenu.storeId.eq(store.id), storeMenu.name.contains(query)).exists()));
        }
        if (cursor != null) {
            double cursorDistance = Double.parseDouble(cursor.sortValue());
            // 반올림 전 거리와 ID를 정렬 순서대로 비교하여 이전 페이지의 마지막 가게 다음부터 조회한다.
            conditions.and(distance.gt(cursorDistance)
                    .or(distance.eq(cursorDistance).and(store.id.gt(cursor.id()))));
        }

        /*
         * 가게 정보와 대표 이미지, 계산 거리, 당일 활성 캠페인과 할인 대상 메뉴를 한 쿼리로 가져온다.
         * 이미지나 캠페인, 메뉴가 없는 가게도 목록에 남도록 LEFT JOIN을 사용한다.
         */
        return queryFactory.select(Projections.constructor(StoreListRow.class,
                        store.id, store.name, store.categoryCode, store.longitude, store.latitude, distance,
                        storeCampaignSummary.campaignId, storeCampaignSummary.discountTargetType,
                        storeCampaignSummary.discountType, storeCampaignSummary.discountValue,
                        storeMenu.id, storeMenu.name, storeMenu.price, storeImage.objectKey))
                .from(store)
                .leftJoin(storeImage).on(storeImage.storeId.eq(store.id),
                        storeImage.imageType.eq(StoreImageType.LOGO),
                        storeImage.uploadStatus.eq(StoreImageUploadStatus.VERIFIED))
                .leftJoin(storeCampaignSummary).on(storeCampaignSummary.storeId.eq(store.id),
                        storeCampaignSummary.businessDate.eq(businessDate),
                        storeCampaignSummary.campaignStatus.eq(StoreCampaignStatus.ACTIVE))
                .leftJoin(storeMenu).on(storeMenu.id.eq(storeCampaignSummary.targetMenuId),
                        storeMenu.storeId.eq(store.id))
                .where(conditions)
                .orderBy(distance.asc(), store.id.asc())
                .limit(limit)
                .fetch();
    }

    /*
     * 사용자 좌표에서 각 가게까지의 구면 거리를 미터로 계산할 SQL 식을 만든다.
     * 실제 계산은 fetch 시 DB에서 실행되며 정렬과 커서는 반올림 전 거리를 사용한다.
     */
    private static NumberExpression<Double> distanceFrom(BigDecimal longitude, BigDecimal latitude) {
        // ST_Distance_Sphere가 허용하는 경도는 (-180, 180]이므로 같은 위치인 -180도를 180도로 정규화한다.
        BigDecimal normalizedLongitude = longitude.compareTo(BigDecimal.valueOf(-180)) == 0
                ? BigDecimal.valueOf(180) : longitude;
        NumberExpression<BigDecimal> storeLongitude = new CaseBuilder()
                .when(store.longitude.eq(BigDecimal.valueOf(-180))).then(BigDecimal.valueOf(180))
                .otherwise(store.longitude);

        /*
         * POINT는 경도, 위도 순서이며 {0}과 {1}은 가게, {2}와 {3}은 사용자 좌표다.
         * Hibernate가 함수 결과를 거리 비교에 사용할 수 있도록 double로 캐스팅한다.
         */
        return Expressions.numberTemplate(Double.class, """
                cast(function('ST_Distance_Sphere',
                    function('POINT', {0}, {1}), function('POINT', {2}, {3})) as double)
                """, storeLongitude, store.latitude, normalizedLongitude, latitude);
    }
}
