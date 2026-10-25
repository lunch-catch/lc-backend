/*
 * 기본 세계 환불 요청의 수수료를 NULL 에서 0 으로 고친다 (#896).
 * 명세(billing-api-spec.md)는 refund_request.fee 에 NULL 이 아니라 0 을 저장한다고 정했고 앱도 요청을 만들 때 0 을 넣는다.
 * V5 가 COMPLETED 가 아닌 요청에 NULL 을 넣어서 승인한 뒤에도 수수료가 NULL 로 남았다.
 * 이미 적용된 V5 는 체크섬 때문에 고치지 않는다. 그래서 그보다 뒤인 이 파일에서 고친다.
 * 기본 세계의 행(refund_request_id = B + r)만 고치고 앱이 만든 행은 건드리지 않는다.
 */

UPDATE refund_request
   SET fee = 0
 WHERE refund_request_id BETWEEN 10000001 AND 10000008
   AND fee IS NULL;
