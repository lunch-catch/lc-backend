# 코드 관용과 패턴

같은 일을 어떤 모양으로 쓰기로 했는지를 소유한다. 품질을 어디까지 해내야 하는지는 [`docs/software-quality/`](../software-quality/quality-attributes.md)가 갖고, 도메인의 경계를 어디에 긋는지는 [설계 문서](../architecture/런치캐치_도메인_구조와_의존성_설계.md)가 갖는다.

**바꾼 경로에 어느 문서가 걸리는지는 [코드 리뷰 기준](../CODEREVIEW.md)이 매핑한다.** 리뷰는 그 매핑으로 적용할 기준을 찾으므로, 여기 문서를 더하거나 이름을 바꾸면 그 표도 함께 고친다.

## 짝으로 읽는다

문서가 둘씩 짝이다. `*-guideline.md` 는 **판정 기준**이고 리뷰가 그것으로 통과와 반려를 가른다. `*-rationale.md` 는 **그렇게 정한 이유**이고 기준을 바꾸려 할 때 읽는다.

| 주제 | 판정 기준 | 근거 | 규칙 번호 |
|---|---|---|---|
| API 설계 | [api-design-guideline](./api-design-guideline.md) | [rationale](./api-design-rationale.md) | `API-` |
| 엔티티 작성 | [entity-creation-guideline](./entity-creation-guideline.md) | [rationale](./entity-creation-rationale.md) | `EC-` |
| 도메인 경계 | [domain-boundary-guideline](./domain-boundary-guideline.md) | [rationale](./domain-boundary-rationale.md) | `DPB-` |
| JPA 연관 관계 | [jpa-association-guideline](./jpa-association-guideline.md) | [rationale](./jpa-association-rationale.md) | `AGG-` |
| 이펙티브 자바 | [effective-java-guideline](./effective-java-guideline.md) | [rationale](./effective-java-rationale.md) | `EJ-` |
| 빌드 게이트 | [build-gate-guideline](./build-gate-guideline.md) | [rationale](./build-gate-rationale.md) | `BLD-` |
| 로깅 (이 저장소 적용편) | [logging-project-guideline](./logging-project-guideline.md) | [rationale](./logging-project-rationale.md) | `LOG-`, `OBS-` |

짝이 없는 문서가 하나다.

| 문서 | 담는 것 |
|---|---|
| [response-exception-flow](./response-exception-flow.md) | 응답 봉투와 예외가 컨트롤러에서 핸들러까지 흐르는 경로 |

## 규칙 번호를 쓰는 이유

기준마다 `API-2-03` 처럼 번호가 붙어 있다. 리뷰 댓글과 PR 본문, 이슈에서 그 번호로 가리키면 어느 기준인지가 한 번에 정해진다. 문장을 옮겨 적으면 원문과 어긋나므로 번호로 가리킨다.

`BLD-` 안에는 다른 문서의 번호(`INF-`)를 인용하는 자리가 있다. 인프라 쪽 기준은 `lc-infra` 가 소유한다.

## 이 폴더에 문서를 더할 때

1. 판정 기준과 근거를 한 파일에 섞지 않는다. `{주제}-guideline.md` 와 `{주제}-rationale.md` 로 나눈다.
2. 기준마다 번호를 붙인다. 접두사는 주제마다 새로 정하고 기존 것과 겹치지 않게 한다.
3. [코드 리뷰 기준](../CODEREVIEW.md)의 표에 그 경로와 내용 시그널을 더한다. 더하지 않으면 리뷰가 그 문서를 찾지 못한다.
4. 위 표에 줄을 더한다.

이 폴더의 문서는 이 저장소만의 것이 아니라 **공통으로 적용되는 기준**이다. 이 저장소의 사정만 담는 내용은 `*-project-*` 처럼 적용편을 따로 두거나 그 도메인의 설계서에 적는다.
