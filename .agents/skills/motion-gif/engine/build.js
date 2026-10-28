#!/usr/bin/env node
/*
 * 장면 정의(scene JSON)를 엔진과 합쳐 혼자 열리는 HTML 하나로 만든다.
 * 사용법: node build.js <scene.json> <out.html>
 * 그리기 전에 잡을 수 있는 실수를 경고한다. 글자 넘침과 겹침은 record.js 가 그린 뒤에 다시 본다.
 */
const fs = require('fs');
const path = require('path');

const [specPath, outPath] = process.argv.slice(2);
if (!specPath || !outPath) {
  console.error('사용법: node build.js <scene.json> <out.html>');
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
<title>${escapeHtml(spec.name || spec.title || 'scene')}</title>
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

function check(s) {
  const out = [];
  const byId = {};
  const ids = new Set();
  (s.elements || []).forEach((e, i) => {
    const name = e.id || `elements[${i}]`;
    if (e.id) {
      if (ids.has(e.id)) out.push(`id 가 겹친다: ${e.id}`);
      ids.add(e.id); byId[e.id] = e;
    }
    if (e.x == null || e.y == null) out.push(`${name} 에 x, y 가 없다`);
    if (e.parent && !byId[e.parent]) out.push(`${name} 의 parent ${e.parent} 가 없거나 이 요소보다 뒤에 있다`);
    if (['chart', 'ring', 'columns', 'wave'].includes(e.type) && (!e.w || !e.h)) out.push(`${name}(${e.type}) 에는 w 와 h 가 있어야 한다`);
  });
  const absOf = (e) => { let x = e.x || 0, y = e.y || 0, p = e.parent; while (p && byId[p]) { x += byId[p].x || 0; y += byId[p].y || 0; p = byId[p].parent; } return { x, y, w: e.w || 0, h: e.h || 0 }; };
  (s.elements || []).forEach((e, i) => {
    const b = absOf(e), name = e.id || `elements[${i}]`;
    if (b.x < 0 || b.y < 0 || b.x + b.w > 1600 || b.y + b.h > 900) out.push(`${name} 가 무대(1600x900) 밖으로 나간다`);
    if (e.parent && byId[e.parent] && byId[e.parent].w) {
      const p = byId[e.parent];
      if ((e.x || 0) < 0 || (e.y || 0) < 0 || (e.x || 0) + (e.w || 0) > p.w || (e.y || 0) + (e.h || 0) > (p.h || 1e9)) out.push(`${name} 가 parent ${e.parent} 밖으로 나간다`);
    }
    const titleBottom = s.title && s.title.includes('\n') ? 180 : 150;
    if (b.y < titleBottom && e.type !== 'label' && !e.parent) out.push(`${name} 가 제목 자리(y < ${titleBottom})와 겹칠 수 있다`);
  });
  (s.wires || []).forEach((w) => { if (w.id) ids.add(w.id); });
  const refs = [];
  (s.wires || []).forEach((w) => w.points.forEach((p) => typeof p === 'string' && refs.push(p.split('.')[0])));
  const walk = (st) => {
    ['hl', 'active', 'progress', 'write', 'cells', 'chip', 'row', 'count', 'meter', 'ring', 'chart', 'columns', 'tab', 'line', 'retone'].forEach((k) => typeof st[k] === 'string' && refs.push(st[k]));
    ['show', 'hide', 'dim', 'undim', 'focus', 'unfocus', 'among'].forEach((k) => st[k] && [].concat(st[k]).forEach((x) => refs.push(x)));
    [st.dot, st.stream].forEach((pth) => pth && pth.forEach((p) => (typeof p === 'string' ? refs.push(p.split('.')[0]) : p && p.wire && refs.push(p.wire))));
    (st.par || []).forEach(walk); (st.seq || []).forEach(walk); (st.repeat || []).forEach(walk);
  };
  (s.timeline || []).forEach((t) => (t.steps || []).forEach(walk));
  [...new Set(refs)].forEach((r) => { if (!ids.has(r)) out.push(`없는 id 를 가리킨다: ${r}`); });
  if (!s.loop) out.push('loop(ms) 가 없어 15000 으로 돈다');
  if (s.title && s.title.replace(/\n/g, '').length > 34 && !s.title.includes('\n')) out.push('제목이 길어 한 줄을 넘을 수 있다. 줄바꿈(\\n)을 넣거나 줄인다');
  return out;
}
