#!/usr/bin/env node
/*
 * 장면 정의(spec JSON)를 엔진과 합쳐 혼자 열리는 HTML 하나로 만든다.
 * 사용법: node build.js <spec.json> <out.html>
 */
const fs = require('fs');
const path = require('path');

const [specPath, outPath] = process.argv.slice(2);
if (!specPath || !outPath) {
  console.error('사용법: node build.js <spec.json> <out.html>');
  process.exit(1);
}
const spec = JSON.parse(fs.readFileSync(specPath, 'utf8'));
const problems = check(spec);
problems.forEach((p) => console.warn('경고: ' + p));

const dir = __dirname;
const css = fs.readFileSync(path.join(dir, 'engine.css'), 'utf8');
const js = fs.readFileSync(path.join(dir, 'engine.js'), 'utf8');
const accent = spec.accent ? `:root{--accent:${spec.accent}}` : '';
const safeJson = JSON.stringify(spec).replace(/</g, '\\u003c');

const html = `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(spec.name || spec.title || 'slide')}</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+KR:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
${css}
${accent}
</style>
</head>
<body>
<div id="wrap"><div id="stage"></div></div>
<script>window.SLIDE_SPEC = ${safeJson};</script>
<script>
${js}
</script>
</body>
</html>
`;
fs.mkdirSync(path.dirname(path.resolve(outPath)), { recursive: true });
fs.writeFileSync(outPath, html);
console.log(`만듦: ${outPath}${problems.length ? ` (경고 ${problems.length}개)` : ''}`);

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

// 그리기 전에 잡을 수 있는 실수를 찾는다. 겹침은 녹화한 정지 화면으로 확인한다
function check(s) {
  const out = [];
  const ids = new Set();
  const boxes = [];
  (s.elements || []).forEach((e, i) => {
    if (e.id) {
      if (ids.has(e.id)) out.push(`id 가 겹친다: ${e.id}`);
      ids.add(e.id);
    }
    if (e.x == null || e.y == null) out.push(`elements[${i}] 에 x, y 가 없다`);
    const w = e.w || 0, h = e.h || 0;
    if (e.x < 0 || e.y < 0 || e.x + w > 1600 || e.y + h > 900) out.push(`${e.id || 'elements[' + i + ']'} 가 무대(1600x900) 밖으로 나간다`);
    if (e.y != null && e.y < 150 && e.type !== 'label') out.push(`${e.id || 'elements[' + i + ']'} 가 제목 자리(y < 150)와 겹칠 수 있다`);
    if (e.type === 'box' && e.id) boxes.push(e);
  });
  (s.wires || []).forEach((w) => { if (w.id) ids.add(w.id); });
  const refs = [];
  (s.wires || []).forEach((w) => w.points.forEach((p) => typeof p === 'string' && refs.push(p.split('.')[0])));
  walk(s.timeline || [], (st) => {
    ['hl', 'active', 'progress', 'text', 'cells', 'chip', 'row'].forEach((k) => st[k] && refs.push(st[k]));
    if (st.dot) st.dot.forEach((p) => (typeof p === 'string' ? refs.push(p.split('.')[0]) : p && p.wire && refs.push(p.wire)));
  });
  refs.forEach((r) => { if (!ids.has(r)) out.push(`없는 id 를 가리킨다: ${r}`); });
  for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
    const a = boxes[i], b = boxes[j];
    if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) out.push(`box 가 겹친다: ${a.id}, ${b.id}`);
  }
  if (!s.loop) out.push('loop(ms) 가 없어 12000 으로 돈다');
  return out;
}
function walk(tracks, fn) {
  const visit = (st) => { fn(st); (st.par || []).forEach(visit); (st.seq || []).forEach(visit); };
  tracks.forEach((t) => (t.steps || []).forEach(visit));
}
