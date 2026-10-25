/*
 * 기본 세계 일별 회원 지표의 정지 수를 0 에서 집계 작업이 세는 값으로 고친다 (#966).
 * 집계 작업은 그날 말까지 정지된 SUSPENDED 회원을 센다. V7 은 그 값을 쓰지 않아 0 으로 넣었다.
 * 이미 적용된 V7 은 체크섬 때문에 고치지 않는다. 그래서 그보다 뒤인 이 파일에서 고친다.
 * 기준일은 다시 돌 때 바뀔 수 있어 쓰지 않고 이미 들어간 회원의 정지 시각으로 센다.
 * 기본 세계의 행(V7 이 business_date 다음 날 01:03 으로 만든 행)만 고치고 집계 작업이 쓴 행은 건드리지 않는다.
 */

UPDATE daily_member_analytics dma
   SET dma.suspended_count = (SELECT COUNT(*) FROM member m
                               WHERE m.member_id BETWEEN 10000001 AND 10003000
                                 AND m.status = 'SUSPENDED'
                                 AND m.suspended_at < DATE_ADD(dma.business_date, INTERVAL 1 DAY))
 WHERE dma.created_at = TIMESTAMP(DATE_ADD(dma.business_date, INTERVAL 1 DAY), '01:03:00')
   AND dma.suspended_count = 0;
