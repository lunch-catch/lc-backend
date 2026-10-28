# 장면 정의(scene) 형식

장면은 JSON 파일 하나다. 무대는 가로 1600, 세로 900 이고 좌표의 단위는 픽셀이다.

```json
{
  "name": "GIF 이름",
  "title": "이 장이 답하는 결론 한 문장입니다.",
  "logo": "LUNCHCATCH",
  "speed": 0.4,
  "loop": 15000,
  "elements": [],
  "wires": [],
  "timeline": [],
  "sources": ["lc-backend: adserving/engine/FeedAssembler.java"]
}
```

| 필드 | 뜻 |
|---|---|
| `title` | 결론 문장이다. 두 줄이면 `\n` 을 넣는다 |
| `speed` | 점의 속도다. 밀리초당 픽셀이고 0.3 ~ 0.5 가 읽기 좋다 |
| `loop` | 한 반복의 길이다. 밀리초이고 GIF 는 이 길이 하나를 찍는다. 시간이 다 되면 모든 상태가 처음으로 돌아간다 |
| `accent` | 강조색을 바꾼다. 생략하면 주황이다 |
| `sources` | 장면의 사실을 가져온 파일이다. 그리지 않고 남겨만 둔다 |

## 요소

모든 요소는 `type`, `x`, `y` 를 가진다. 다른 요소나 단계가 가리키려면 `id` 를 준다.

**`parent` 를 주면 좌표가 그 요소의 왼쪽 위 기준이 된다.** 패널 안 요소는 parent 로 두면 패널을 옮길 때 함께 움직인다. parent 는 그 요소보다 앞에 적는다.
`panel` 은 언제나 맨 아래에 깔리고 선은 그 위, 나머지 요소는 선 위에 그려진다.

공통 필드는 아래와 같다.

| 필드 | 뜻 |
|---|---|
| `hidden: true` | 처음에 숨긴다. `show` 단계로 나타난다 |
| `dim: true` | 처음에 흐리게 둔다 |
| `tone` | 의미색이다. `good`, `bad`, `accent`, 그 밖에 요소마다 허용한 값 |

| type | 쓰는 곳 | 필드 |
|---|---|---|
| `panel` | 큰 영역 | `w`, `h`, `eyebrow`, `title`, `sub`, `dashed`, `bare`(면과 테두리 없음), `tone` |
| `box` | 모듈, 서버, 단계 하나 | `w`, `h`, `title`, `sub`, `value`(셋 모두 `\n` 줄바꿈), `num`, `icon`, `progress`(0~1), `size`(`s`, `m`, `l`), `align: center`, `tone`(`good`, `bad`, `accent`, `ghost`, `dim`). `accent` 는 주황으로 꽉 채우고 글자를 어둡게 쓴다. 장면에서 가장 중요한 상자 하나에만 주고 설명은 짧게 둔다 |
| `label` | 상자 밖 글자 | `text`(`\n` 줄바꿈), `color`(`dim`, `mid`, `fg`, `accent`, `good`, `bad`), `size`(`xs`, `s`, `m`, `l`, `xl`, `xxl`), `mono`, `align`, `w`(주면 줄바꿈) |
| `stat` | 큰 숫자 하나 | `label`, `caption`, `value`(숫자나 글자), `decimals`, `prefix`, `unit`, `sub`, `size`(`m`, `l`, `xl`), `tone` |
| `meter` | 가로 막대와 기준선 | `w`, `h`(기준 글자가 있으면 74 이상), `label`, `value`, `max`, `threshold`, `thresholdLabel`, `text`(값 대신 보일 글자), `unit`, `decimals`, `tone`. 기준을 넘은 부분은 장미색이다. `overflow: false` 면 끈다 |
| `ring` | 고리 비율 | `w`, `h`, `value`(0~1), `text`, `sub`, `stroke`, `tone` |
| `chart` | 선 그래프 | `w`, `h`, `series`(`[{ values, tone, dash, label }]`), `min`, `max`, `xLabels`, `yLabel`, `threshold`, `thresholdLabel`, `thresholdTone`, `smooth`(기본 true), `reveal`(0 이면 처음에 안 보이고 `draw` 로 그린다) |
| `columns` | 세로 막대 | `w`, `h`, `values`, `labels`, `texts`, `max`, `on`(강조할 칸 번호), `tone` |
| `tabs` | 위쪽 탭 줄 | `items`, `index` |
| `code` | 코드 블록 | `w`, `h`, `lines`(글자, 또는 `{ text, tone }`. tone 은 `add`, `del`, `on`). `//` 뒤는 주석색 |
| `icon` | 동그란 아이콘 하나 | `icon`, `size`, `label`, `plain`, `tone`(`good`, `bad`, `accent`, `mint`) |
| `wave` | 소리 파형 | `w`, `h`, `n`, `gap`, `amp`, `tone`. 시간에 따라 계속 출렁인다 |
| `chips` | 칩 줄 | `items`(글자, 또는 `{ text, tone }`. tone 은 `good`, `bad`, `accent`, `ghost`), `joined`(기본 true, 사이에 선), `gap`, `size: l` |
| `cells` | 칸 격자 | `n`, `cols`, `size`, `cellW`, `gap`, `fill`(처음 색 목록) |
| `rows` | 표 | `w`, `rowH`, `gap`, `items`(`{ label, bar, meta, tone, on }`). 표의 높이는 `rowH x 줄 수 + gap x (줄 수 - 1)` 이다. `tone` 을 준 줄은 켜지지 않아도 오른쪽 글자(meta)가 그 색이다 |
| `formula` | 수식 | `html`. 아래첨자 `<sub>`, 분수 `<span class="frac"><span>분자</span><span>분모</span></span>` |

`icon` 이름: `scan`, `chart`, `upload`, `play`, `shield`, `gauge`, `db`, `server`, `clock`, `bolt`, `user`, `robot`, `queue`, `coin`, `bell`, `cloud`, `lock`, `chip`, `sun`, `moon`, `code`, `phone`, `store`, `ticket`, `check`, `x`.

점과 칸의 색 이름: `fg`, `dim`, `good`, `bad`, `accent`, `a`(민트), `b`(보라), `c`(밝은 회색), `warn`(노랑).

## 기준점

선과 점의 경로에는 좌표 대신 `{id}.{자리}` 를 쓴다. 자리는 `c`(가운데), `l`, `r`, `t`, `b`(변 가운데), `tl`, `tr`, `bl`, `br`(모서리)다.
뒤에 `+40`, `-130` 하나를 붙이면 변을 따라 옮긴다. `t`, `b` 는 가로로, `l`, `r` 은 세로로 옮긴다. 둘을 붙이면(`c+10-20`) x, y 로 옮긴다.
parent 안 요소도 기준점은 무대 좌표로 계산된다.

## 선 (wires)

| 필드 | 뜻 |
|---|---|
| `id` | 점이 이 선을 따라가게 하려면 준다 |
| `points` | 기준점이나 좌표의 목록 |
| `elbow` | 축이 어긋난 두 점을 꺾는 방식이다. `hvh`(기본), `vhv`, `hv`, `vh`, `none` |
| `dash`, `arrow`, `tone`(`accent`, `good`, `bad`), `hidden` | 점선, 화살표, 색, 처음에 숨김 |
| `label` | `{ "text": "", "at": 기준점, "dx": 0, "dy": -8, "align": "middle" }` |

선은 상자 아래에 그려진다. 다른 상자를 가로지르게 두지 않는다.

## timeline

트랙 목록이다. 트랙마다 `at`(시작 밀리초)과 `steps` 가 있고 단계는 차례로 돈다.
`every` 를 주면 그 간격으로 `steps` 를 되풀이한다. `until` 을 주면 반복의 끝에서 그만큼 앞서 멈춘다.

| 단계 | 뜻 | 기다림 |
|---|---|---|
| `{ "dot": [경로], "color": "good" }` | 점 하나가 경로를 따라간다. 경로에 선은 `{ "wire": "id", "rev": true }` 로 넣는다. `r`(크기, 기본 4.5), `speed`(이 점만의 속도), `keep: true`(도착한 뒤 남긴다)를 줄 수 있다 | 도착까지 |
| `{ "stream": [경로], "count": 5, "gap": 220 }` | 같은 경로로 점 여러 개를 간격을 두고 보낸다 | 마지막 점 도착까지 |
| `{ "show": "id" }`, `{ "hide": ["a", "b"] }` | 0.4초 동안 나타나고 사라진다. 선에도 쓴다. `fade` 로 걸리는 시간을, `ms` 로 기다릴 시간을 준다 | `ms` |
| `{ "dim": [...] }`, `{ "undim": [...] }` | 흐리게 하고 되돌린다 | 없음 |
| `{ "focus": ["a"], "among": ["a", "b", "c"] }` | among 가운데 focus 만 밝히고 나머지를 흐린다. among 이 없으면 같은 parent 형제끼리다 | 없음 |
| `{ "unfocus": [...] }` | 흐림을 푼다 | 없음 |
| `{ "hl": "id", "ms": 600, "hold": true }` | 잠깐 밝힌다. hold 면 그만큼 기다린다 | hold 일 때 |
| `{ "active": "id", "on": false }` | 켠 채로 두고 끈다. 켜진 상자는 테두리와 면이 밝아진다. 이것만으로 약하면 `retone` 으로 의미색을 함께 준다 | 없음 |
| `{ "retone": "id", "to": "good" }` | 의미색을 바꾼다. `to: null` 이면 뺀다 | 없음 |
| `{ "write": "id", "field": "value", "value": "" }` | 글자를 바꾼다. field 는 `title`, `sub`, `value` 이고 `label`, `stat`, `meter`, `ring`, `icon` 은 `value` 하나다. 처음에 비어 있던 칸에도 쓸 수 있다. 없는 field 면 멈추고 알린다 | 없음 |
| `{ "count": "id", "to": 0.31, "ms": 900, "decimals": 2 }` | `stat` 숫자를 세며 바꾼다. `from` 을 주면 그 값에서 시작한다 | 끝까지 |
| `{ "meter": "id", "to": 1400, "text": "1.40" }` | 막대를 바꾼다. text 가 없으면 막대와 함께 숫자를 센다. text 를 주면 막대가 다 찬 뒤에 그 글자로 바뀌므로 단위를 바꿔 보일 때만 준다 | 끝까지 |
| `{ "ring": "id", "to": 0.954, "text": "95.4%" }` | 고리를 바꾼다 | 끝까지 |
| `{ "chart": "id", "draw": true, "ms": 1600 }` | 선을 왼쪽부터 그린다 | 끝까지 |
| `{ "chart": "id", "series": 0, "values": [...] }` | 선의 값을 바꿔 모양을 옮긴다 | 끝까지 |
| `{ "columns": "id", "values": [...], "on": [3], "texts": [...] }` | 막대 높이, 강조, 글자를 바꾼다 | 끝까지 |
| `{ "progress": "id", "to": 1, "ms": 800 }` | 상자 진행 바를 채운다 | 끝까지 |
| `{ "tab": "id", "index": 2 }` | 탭을 넘긴다 | 없음 |
| `{ "line": "id", "index": [3, 4], "to": "add" }` | 코드 줄 모양을 바꾼다. `to: null` 이면 되돌린다 | 없음 |
| `{ "cells": "id", "push": "good" }` | 빈 칸 하나를 채운다. `pop: true` 는 앞 칸을 빼고 당긴다. `fillTo: 63, color: "fg"` 는 앞에서부터 63칸만 칠한다. `paint: [2, 5], color: "bad"` 는 그 칸만, `set: [...]` 은 통째로 바꾼다 | 없음 |
| `{ "chip": "id", "index": 1 }` | 칩 하나를 켠다. -1 이면 끈다. `to` 를 주면 그 칩의 의미색을 바꾼다 | 없음 |
| `{ "row": "id", "index": 0, "meta": "", "bar": 0.4, "tone": "good", "label": "" }` | 표 한 줄을 강조하고 값을 바꾼다. `on: true/false` 는 그 줄만 켜고 끈다. `focus: false` 는 강조 없이 값만 바꾼다 | 없음 |
| `{ "wait": 500 }` | 기다린다 | 그 시간 |
| `{ "par": [...] }`, `{ "seq": [...] }` | 함께 시작해 모두 끝나면 넘어간다. 묶음 안을 차례로 돈다 | 끝까지 |
| `{ "repeat": [...], "times": 6 }` | 묶음을 그 횟수만큼 되풀이한다 | 끝까지 |

어느 단계든 `"async": true` 를 붙이면 기다리지 않고 다음 단계로 넘어간다.
점은 상자 가운데에서 출발하고 가운데에서 멈추게 경로를 짠다. 상자 뒤를 지날 때도 같은 속도로 가서 상자가 처리하는 시간이 생긴다.

## 점검

`build.js` 는 겹치는 id, 없는 id 를 가리키는 참조, 무대나 parent 밖으로 나가는 요소, 제목 자리를 침범하는 요소, 긴 제목을 경고한다.
`record.js` 는 그린 뒤에 상자를 넘친 글자, parent 밖으로 나간 글자, 같은 parent 안에서 겹친 요소, 상자나 표 위에 올라앉은 글자를 경고한다. 정지 화면을 찍을 때마다, 그리고 GIF 를 찍는 도중 한 번 본다.
경고가 하나라도 있으면 고친 뒤에 GIF 를 찍는다.
