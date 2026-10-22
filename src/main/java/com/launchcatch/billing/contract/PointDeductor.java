package com.launchcatch.billing.contract;

import java.time.LocalDate;
import java.util.UUID;

/*
 * 광고 서빙이 유효 노출 1건의 과금을 정산에 맡기는 계약이다(I-01).
 *
 * 노출을 저장하는 트랜잭션 안에서만 부른다. 판정 기록, 원장, 노출이 함께 커밋되고 함께 롤백된다.
 * 구현은 트랜잭션 밖 호출을 바로 실패시킨다(Propagation.MANDATORY).
 * 1단계 판정이 VALID 인 노출만 넘긴다. 무효 노출과 품절 노출은 부르지 않는다.
 */
public interface PointDeductor {

    /*
     * 노출 1건의 과금 여부를 판정하고 기록한다.
     *
     * serveId 는 서빙 토큰의 ID 그대로이고 노출 1건에 하나다. businessDate 는 노출 요청을 서버가 받은 날(KST)이다.
     * 카드를 내려준 날이 아니다. 23:59 에 내려준 카드의 노출이 00:00 뒤에 오면 새 날로 넘긴다. 전날 예산은 00:00 에
     * 해제되므로 그 뒤에 전날로 차감하면 돌려준 예산에서 빠진다.
     * 같은 serveId 로 다시 부르면 첫 판정을 그대로 돌려주고 성공한다. 판정 기록과 카운터 증가는 한 번만 남는다.
     * 예약액에 닿으면 과금만 멈추고 캠페인 상태는 바꾸지 않는다. 잔액은 읽지 않는다.
     *
     * 그날 예산 계획이 없는 캠페인이면 NOT_BILLED_NO_PLAN 판정을 남기고 돌려준다. 00:00 예약이 그 캠페인의 계획을
     * 만들기 전이거나 전날 집행이 끝난 캠페인이다. 캠페인 자체가 없으면 IllegalStateException 을 던진다.
     * 카운터나 DB 쓰기가 실패하면 DataAccessException 계열을 던진다. 예외면 노출 저장까지 롤백된다.
     */
    DeductionStatus deduct(UUID serveId, Long campaignId, LocalDate businessDate);
}
