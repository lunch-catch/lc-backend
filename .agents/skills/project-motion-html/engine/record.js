#!/usr/bin/env node
/*
 * build.js 가 만든 HTML 을 프레임 단위로 찍어 GIF 하나로 만든다. 다른 파일은 남기지 않는다.
 * 브라우저 시계를 멈춘 채 한 프레임씩 넘기므로 컴퓨터 속도와 무관하게 끊김 없이 나온다.
 * 그린 뒤에 글자 넘침과 요소 겹침을 재서 경고한다.
 *
 * 사용법:
 *   node record.js <scene.html> <출력.gif>                 GIF 를 만든다
 *   node record.js <scene.html> <출력.png> --still 3000    3초 시점의 정지 화면 하나와 배치 점검만 한다
 * 선택: --fps 25, --width 1280
 */
const fs = require('fs');
const path = require('path');
const { spawn, spawnSync } = require('child_process');
const { chromium } = require('playwright');

const args = process.argv.slice(2);
const [htmlPath, outFile] = args;
const opt = (name, def) => { const i = args.indexOf('--' + name); return i < 0 ? def : args[i + 1]; };
if (!htmlPath || !outFile) {
  console.error('사용법: node record.js <scene.html> <출력.gif|출력.png> [--still ms] [--fps 25] [--width 1280]');
  process.exit(1);
}
const FPS = +opt('fps', 25);
const GIF_W = +opt('width', 1280);
const STILL = opt('still', null);

function ffmpegPath() {
  try { const p = require('ffmpeg-static'); if (p && fs.existsSync(p)) return p; } catch (e) { /* 없으면 시스템 ffmpeg 를 쓴다 */ }
  if (spawnSync('ffmpeg', ['-version']).status === 0) return 'ffmpeg';
  console.error('ffmpeg 를 찾지 못했다. engine 폴더에서 npm install 을 다시 하거나 ffmpeg 를 설치한다.');
  process.exit(1);
}

// 구글 폰트 대신 설치된 폰트 파일을 쓴다. 녹화 환경이 외부 망을 못 써도 글꼴이 같게 나온다
function localFonts() {
  const base = path.join(__dirname, 'node_modules', '@fontsource');
  const face = (family, pkg, sub, w) => {
    const f = path.join(base, pkg, 'files', `${pkg}-${sub}-${w}-normal.woff2`);
    return fs.existsSync(f) ? `@font-face{font-family:"${family}";font-weight:${w};src:url(data:font/woff2;base64,${fs.readFileSync(f).toString('base64')}) format("woff2")}` : '';
  };
  let css = '';
  for (const w of [400, 500, 600, 700]) for (const sub of ['korean', 'latin']) css += face('IBM Plex Sans KR', 'ibm-plex-sans-kr', sub, w);
  for (const w of [400, 500]) css += face('IBM Plex Mono', 'ibm-plex-mono', 'latin', w);
  return css;
}

// 그려진 화면에서 넘친 글자와 겹친 요소를 찾는다
function layoutCheck() {
  const out = [];
  const els = [...document.querySelectorAll('#stage .el')];
  const rect = (e) => e.getBoundingClientRect();
  const name = (e) => e.dataset.id || e.className.split(' ')[0];
  const stage = document.getElementById('stage').getBoundingClientRect();
  const sc = stage.width / 1600;
  els.forEach((e) => {
    const r = rect(e);
    if (e.scrollWidth > e.clientWidth + 2 || e.scrollHeight > e.clientHeight + 2) {
      if (!e.classList.contains('label') && !e.classList.contains('chips') && !e.classList.contains('formula')) out.push(`${name(e)}: 안의 내용이 상자를 넘친다`);
    }
    [...e.querySelectorAll('*')].forEach((c) => {
      const q = rect(c);
      if (!q.width || !q.height) return;
      if (q.right > r.right + 3 * sc || q.bottom > r.bottom + 3 * sc || q.left < r.left - 3 * sc) {
        if (!e.classList.contains('ring') && !c.closest('svg')) out.push(`${name(e)}: 안의 "${(c.textContent || '').trim().slice(0, 20)}" 가 밖으로 나간다`);
      }
    });
    if (e.classList.contains('label') || e.classList.contains('chips') || e.classList.contains('formula')) {
      const p = e.dataset.parent && document.querySelector(`[data-id="${e.dataset.parent}"]`);
      if (p) { const pr = rect(p); if (r.right > pr.right + 2 * sc || r.bottom > pr.bottom + 2 * sc) out.push(`${name(e)}: 글자가 parent ${e.dataset.parent} 밖으로 나간다`); }
      if (r.right > stage.right || r.bottom > stage.bottom) out.push(`${name(e)}: 글자가 무대 밖으로 나간다`);
    }
  });
  // 같은 부모를 가진 요소끼리 겹치는지 본다. panel 과 label 은 빼고 본다
  const solid = els.filter((e) => !e.classList.contains('panel') && !e.classList.contains('label') && !e.classList.contains('hidden'));
  for (let i = 0; i < solid.length; i++) for (let j = i + 1; j < solid.length; j++) {
    const a = solid[i], b = solid[j];
    if (a.dataset.parent !== b.dataset.parent) continue;
    const p = rect(a), q = rect(b);
    const ox = Math.min(p.right, q.right) - Math.max(p.left, q.left), oy = Math.min(p.bottom, q.bottom) - Math.max(p.top, q.top);
    if (ox > 4 * sc && oy > 4 * sc) out.push(`${name(a)} 와 ${name(b)} 가 겹친다`);
  }
  // 글자가 같은 부모의 상자나 표 위에 올라앉았는지 본다
  const labels = els.filter((e) => e.classList.contains('label') && !e.classList.contains('hidden') && e.textContent.trim());
  labels.forEach((l) => solid.forEach((b) => {
    if (l.dataset.parent !== b.dataset.parent) return;
    const p = rect(l), q = rect(b);
    const ox = Math.min(p.right, q.right) - Math.max(p.left, q.left), oy = Math.min(p.bottom, q.bottom) - Math.max(p.top, q.top);
    if (ox > 2 * sc && oy > 2 * sc) out.push(`글자 "${l.textContent.trim().slice(0, 16)}" 가 ${name(b)} 와 겹친다`);
  }));
  return [...new Set(out)];
}

(async () => {
  let html = fs.readFileSync(htmlPath, 'utf8').replace(/<link[^>]+fonts\.(googleapis|gstatic)[^>]*>/g, '');
  html = html.replace('</style>', localFonts() + '</style>');
  const tmpDir = path.dirname(path.resolve(outFile));
  fs.mkdirSync(tmpDir, { recursive: true });
  const tmp = path.join(tmpDir, `.rec-${process.pid}.html`);
  fs.writeFileSync(tmp, html);
  const cleanup = (...files) => files.forEach((f) => { try { fs.unlinkSync(f); } catch (e) { /* 이미 없다 */ } });

  const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.clock.install({ time: Date.now() });
  await page.goto('file://' + tmp);
  await page.evaluate(() => document.fonts.ready);
  await page.clock.pauseAt(Date.now() + 1000);
  // 시계를 멈춘 뒤 처음부터 다시 시작해야 첫 프레임이 0초가 되고 GIF 가 이음매 없이 돈다
  // CSS 전환은 실제 시간으로 돌아 멈춘 시계와 어긋난다. 녹화에서는 끄고 엔진의 시계 움직임만 쓴다
  await page.evaluate(() => { document.documentElement.classList.add('recording'); return window.SLIDE && window.SLIDE.ready && window.SLIDE.restart(); });
  const state = await page.evaluate(() => ({ loop: window.SLIDE && window.SLIDE.loop, error: window.SLIDE && window.SLIDE.error }));
  if (state.error || errors.length) {
    console.error('장면을 그리지 못했다: ' + (state.error || errors.join('\n')));
    await browser.close(); cleanup(tmp); process.exit(2);
  }

  const report = async (label) => {
    const w = await page.evaluate(layoutCheck);
    w.forEach((x) => console.warn(`배치 경고(${label}): ${x}`));
    return w.length;
  };

  if (STILL != null) {
    await page.clock.runFor(+STILL);
    await page.screenshot({ path: outFile });
    await report(`${STILL}ms`);
    console.log('만듦: ' + outFile);
    await browser.close(); cleanup(tmp); return;
  }

  const ff = ffmpegPath();
  const total = Math.round((state.loop / 1000) * FPS);
  const raw = path.join(tmpDir, `.rec-${process.pid}.mkv`);
  // GIF 로 바꾸기 전의 중간 파일이다. 손실 없이 모았다가 팔레트를 뽑은 뒤 지운다
  const enc = spawn(ff, ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-i', '-', '-c:v', 'ffv1', raw], { stdio: ['pipe', 'ignore', 'inherit'] });
  let warned = 0;
  for (let i = 0; i < total; i++) {
    enc.stdin.write(await page.screenshot({ type: 'png' }));
    if (i === Math.round(total / 2)) warned += await report('중간');
    await page.clock.runFor(1000 / FPS);
    if (i % FPS === 0) process.stdout.write(`\r녹화 ${Math.round((i / total) * 100)}%`);
  }
  enc.stdin.end();
  await new Promise((r) => enc.on('close', r));
  await browser.close(); cleanup(tmp);
  process.stdout.write('\r녹화 100%\n');

  const vf = `fps=${FPS},scale=${GIF_W}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=160:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle`;
  const r = spawnSync(ff, ['-y', '-loglevel', 'error', '-i', raw, '-vf', vf, '-loop', '0', outFile], { stdio: 'inherit' });
  cleanup(raw);
  if (r.status !== 0) process.exit(r.status);
  console.log(`만듦: ${outFile} (${(fs.statSync(outFile).size / 1048576).toFixed(1)}MB, ${(state.loop / 1000).toFixed(1)}초)${warned ? ` 배치 경고 ${warned}개` : ''}`);
})();
