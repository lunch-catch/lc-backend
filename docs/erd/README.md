# 도메인별 ERD

마이그레이션을 실제 MySQL 에 적용한 뒤 `information_schema` 에서 생성한 문서들이다.
**직접 고치지 않는다.** 스키마를 바꾼 뒤 `./gradlew generateErd` 로 다시 뽑는다.
`G-BUILD` 가 이 문서들과 스키마가 어긋났는지 검사한다.

| 도메인 | 테이블 | 문서 |
|---|---|---|
| admin | 1 | [admin.md](./admin.md) |
| adserving | 8 | [adserving.md](./adserving.md) |
| analytics | 13 | [analytics.md](./analytics.md) |
| billing | 9 | [billing.md](./billing.md) |
| campaign | 7 | [campaign.md](./campaign.md) |
| coupon | 5 | [coupon.md](./coupon.md) |
| member | 3 | [member.md](./member.md) |
| notification | 5 | [notification.md](./notification.md) |
| ops | 4 | [ops.md](./ops.md) |
| owner | 1 | [owner.md](./owner.md) |
| store | 7 | [store.md](./store.md) |
| **합계** | **63** | |

경계를 넘는 참조는 FK 가 아니라 ID 값이라(설계 문서 1.1절) 각 그림이 자기만으로 완결된다.
도메인을 넘는 FK 가 생기면 그 선은 그려지지 않는다. 그것은 그림의 문제가 아니라
`ArchitectureTest` 가 잡아야 하는 의존 방향 위반이다.
