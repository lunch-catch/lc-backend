# 장면 정의(spec) 형식

spec 은 JSON 파일 하나다. 무대는 가로 1600, 세로 900 이고 좌표의 단위는 픽셀이다.
제목이 y 78 근처에 그려지므로 상자는 y 150 아래에 둔다.

```json
{
  "name": "GIF 이름",
  "title": "슬라이드가 말하는 결론 한 문장입니다.",
  "logo": "LUNCHCATCH",
  "category": "워커",
  "accent": "#e8925a",
  "speed": 0.45,
  "loop": 24000,
  "elements": [],
  "wires": [],
  "timeline": [],
  "sources": ["lc-backend: adserving/engine/FeedAssembler.java"]
}
```

| 필드 | 뜻 |
|---|---|
| `speed` | 점이 움직이는 속도다. 밀리초당 픽셀이고 0.3 ~ 0.5 가 읽기 좋다 |
| `loop` | 한 반복의 길이다. 밀리초이고 이 시간이 지나면 모든 상태가 처음으로 돌아간다. GIF 는 이 길이 하나를 찍는다 |
| `accent` | 강조색이다. 생략하면 주황이다 |
| `sources` | 장면의 사실을 가져온 파일이다. 그리지 않고 기록만 한다 |

## elements

모든 요소는 `type`, `x`, `y` 를 가진다. 다른 요소나 timeline 이 가리키려면 `id` 를 준다.
뒤에 적은 요소가 위에 그려진다. `panel` 은 언제나 맨 아래에 깔린다.

| type | 쓰는 곳 | 필드 |
|---|---|---|
| `panel` | 여러 상자를 묶는 큰 영역 | `w`, `h`, `title`, `sub`, `dashed`, `tone`(`accent`) |
| `box` | 모듈, 서버, 단계 하나 | `w`, `h`, `title`, `sub`, `value`, `num`, `icon`, `progress`(0~1), `size`(`s`, `m`, `l`), `align`(`center`), `tone`(`accent`, `ghost`, `dim`). `accent` 는 주황으로 꽉 채우고 글자를 어둡게 쓴다. 장면에서 가장 중요한 상자 하나에만 준다 |
| `label` | 상자 밖의 짧은 설명 | `text`, `color`(`dim`, `mid`, `fg`, `accent`), `size`(`xs`, `s`, `m`, `l`), `mono`, `align` |
| `formula` | 판정 수식 | `html`, `size`(`m`, `l`). 아래첨자는 `<sub>`, 분수는 `<span class="frac"><span>분자</span><span>분모</span></span>`. 영문과 숫자는 세리프, 한글은 본문 글꼴로 나온다. 한글 단어는 분수 안이나 기호 풀이 `label` 에 두는 것이 읽기 좋다 |
| `chips` | 상자 안의 하위 단계 나열 | `items`(글자 목록), `joined`(기본 true, 사이에 선을 긋는다) |
| `cells` | 대기열, 슬롯, 캐시 칸 | `n`, `cols`, `size`, `gap`, `fill`(처음 색 목록) |
| `rows` | 후보나 워커의 표 | `w`, `rowH`, `gap`, `items`(`label`, `bar`, `meta`, `tone`) |

`icon` 은 `scan`, `chart`, `upload`, `play`, `shield`, `gauge`, `db`, `server`, `clock`, `bolt`, `user`, `queue`, `coin`, `bell`, `cloud`, `lock` 중에서 고른다.

색 이름(`cells` 의 칸, 점, `rows` 의 `tone`)은 `a`(민트), `b`(보라), `c`(밝은 회색), `accent`, `warn`(노랑), `fg`, `dim` 이다.
한 장면에서 색은 의미 하나에 하나만 쓴다. 예를 들어 `a` 를 배분 슬롯에 썼으면 다른 뜻으로 `a` 를 쓰지 않는다.

### 상자 높이

상자 안의 줄 수에 따라 높이가 모자라면 진행 바가 사라진다. 아래 값보다 작게 두지 않는다.

| 상자 안 | `size: s` 최소 높이 | `size: m` 최소 높이 |
|---|---|---|
| title 만 | 48 | 56 |
| title + sub | 66 | 76 |
| title + sub + value | 90 | 100 |
| title + sub + value + progress | 112 | 122 |
| icon + num + title + sub + progress | 150 | 160 |

## 기준점

선과 점의 경로에는 좌표 `[x, y]` 대신 기준점 문자열을 쓸 수 있다. `{id}.{자리}` 꼴이다.

| 자리 | 뜻 |
|---|---|
| `c` | 가운데 |
| `l`, `r`, `t`, `b` | 왼쪽, 오른쪽, 위, 아래 변의 가운데 |
| `tl`, `tr`, `bl`, `br` | 네 모서리 |

뒤에 `+40`, `-130` 을 붙이면 변을 따라 옮긴다. `t`, `b` 는 가로로, `l`, `r` 은 세로로 옮긴다.
`app3.b-130` 은 app3 아래 변 가운데에서 왼쪽으로 130 이다.

## wires

| 필드 | 뜻 |
|---|---|
| `id` | 점이 이 선을 따라가게 하려면 준다 |
| `points` | 기준점이나 좌표의 목록이다 |
| `elbow` | 축이 어긋난 두 점을 꺾는 방식이다. `hvh`(기본, 가로 세로 가로), `vhv`, `hv`, `vh`, `none`(꺾지 않고 곧게) |
| `dash` | 점선이다. 제어 신호, 되돌아가는 길, 아직 없는 연결에 쓴다 |
| `arrow` | 끝에 화살표를 단다 |
| `tone` | `accent` 면 강조색이다. 장면에서 가장 중요한 선 하나에만 쓴다 |
| `label` | `{ "text": "", "at": 기준점, "dx": 0, "dy": -8, "align": "middle" }` |

선은 상자 아래에 그려진다. 상자 뒤로 지나가는 선은 보이지 않으므로 다른 상자를 가로지르게 두지 않는다.

## timeline

timeline 은 트랙의 목록이다. 트랙마다 `at`(시작 밀리초)과 `steps` 가 있고, 단계는 차례로 돈다.
`every` 를 주면 그 간격으로 `steps` 를 반복한다. 요청이 계속 들어오는 배경 움직임에 쓴다.

| 단계 | 뜻 | 기다림 |
|---|---|---|
| `{ "dot": [경로], "color": "a" }` | 점 하나가 경로를 따라 움직인다 | 도착까지 |
| `{ "hl": "id", "ms": 600 }` | 잠깐 밝힌다. `hold: true` 면 그 시간만큼 기다린다 | 없음 |
| `{ "active": "id", "on": true }` | 켠 채로 둔다. `on: false` 로 끈다 | 없음 |
| `{ "progress": "id", "to": 1, "ms": 800 }` | 상자의 진행 바를 채운다 | 끝까지 |
| `{ "text": "id", "field": "value", "value": "" }` | 상자의 `title`, `sub`, `value` 를 바꾼다 | 없음 |
| `{ "cells": "id", "push": "a" }` | 빈 칸 하나를 채운다. `pop: true` 는 앞 칸을 빼고 당긴다. `paint: 3, color: "a"` 는 그 칸만 칠한다. `set: []` 은 통째로 바꾼다 | 없음 |
| `{ "chip": "id", "index": 1 }` | 칩 하나를 켠다. -1 이면 모두 끈다 | 없음 |
| `{ "row": "id", "index": 0, "meta": "배분", "bar": 0.4, "tone": "b", "label": "요청 6" }` | 표의 한 줄을 강조하고 값을 바꾼다. `tone` 은 강조 색이고 그 줄이 보낼 갈래의 색과 맞춘다. `focus: false` 면 강조하지 않고 값만 바꾼다. index -1 이면 강조를 끈다 | 없음 |
| `{ "wait": 500 }` | 기다린다 | 그 시간 |
| `{ "par": [단계...] }` | 여러 단계를 함께 시작하고 모두 끝나면 넘어간다 | 가장 긴 것 |
| `{ "seq": [단계...] }` | 묶음 안을 차례로 돈다. `par` 안에서 갈래 하나를 만들 때 쓴다 | 끝까지 |

어느 단계든 `"async": true` 를 붙이면 기다리지 않고 다음으로 넘어간다.

### 점의 경로

`dot` 의 경로에는 기준점, 좌표, 선을 섞어 쓴다. 선은 `{ "wire": "id" }` 로 넣고 거꾸로 가려면 `"rev": true` 를 준다.

```json
{ "dot": ["req.c", { "wire": "w0" }, "router.c"] }
```

이 경로는 req 의 가운데에서 출발해 w0 선을 따라가고 router 의 가운데에서 멈춘다.
상자 가운데에서 변까지는 상자 뒤라 보이지 않지만 점은 같은 속도로 지나간다. 그래서 상자가 처리하는 시간이 자연스럽게 생긴다.

## 확인

`build.js` 는 겹치는 id, 없는 id 를 가리키는 참조, 무대 밖 요소, 겹치는 상자를 경고한다.
글자가 넘치는지와 선이 글자를 지나는지는 잡지 못하므로 정지 화면을 직접 본다.
