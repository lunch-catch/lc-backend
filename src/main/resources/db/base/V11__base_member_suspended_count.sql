/*
 * 기본 세계 일별 회원 지표의 정지 수를 집계 작업과 같은 기준으로 채운다 (#867).
 * 집계 작업은 그날 말까지 정지된 회원을 세는데 V7 은 D-1 의 정지 수를 0 으로 넣었다.
 * 기본 세계의 정지 회원(2951 ~ 2980)은 모두 D-1 에 정지되므로 그날의 행만 고친다.
 * 이미 적용된 V7 은 체크섬 때문에 고치지 않는다. 그래서 그보다 뒤인 이 파일에서 고친다.
 * 기준일은 정지 시각에서 읽는다. 이 파일이 기본 세계를 넣은 날보다 늦게 돌아도 그 세계의 D-1 을 고친다.
 */

UPDATE daily_member_analytics dma
  JOIN (SELECT DATE(suspended_at) AS business_date, COUNT(*) AS suspended
          FROM member
         WHERE member_id BETWEEN 10002951 AND 10002980
           AND status = 'SUSPENDED'
         GROUP BY DATE(suspended_at)) s ON s.business_date = dma.business_date
   SET dma.suspended_count = s.suspended
 WHERE dma.suspended_count = 0;
