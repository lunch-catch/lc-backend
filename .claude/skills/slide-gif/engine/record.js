#!/usr/bin/env node
/*
 * build.js 가 만든 HTML 을 프레임 단위로 찍어 GIF 로 만든다.
 * 프레임은 먼저 화질 손실이 적은 임시 영상으로 모은 뒤 GIF 로 바꾸고, 임시 영상은 지운다.
 * 브라우저 시계를 멈춘 채 한 프레임씩 넘기므로 컴퓨터 속도와 무관하게 끊김 없이 나온다.
 *
 * 사용법:
 *   node record.js <slide.html> <출력 이름>                 GIF 를 만든다
 *   node record.js <slide.html> <출력 이름> --still 3000    3초 시점의 정지 화면 PNG 하나만 만든다
 * 선택: --loops 2 (반복 횟수, 기본 1), --fps 25, --gif-width 1280
 */
const fs = require('fs');
const path = require('path');
const { spawn, spawnSync } = require('child_process');
const { chromium } = require('playwright');

const args = process.argv.slice(2);
const [htmlPath, outBase] = args;
const opt = (name, def) => { const i = args.indexOf('--' + name); return i < 0 ? def : args[i + 1]; };
if (!htmlPath || !outBase) {
  console.error('사용법: node record.js <slide.html> <출력 이름> [--still ms] [--loops n] [--fps 25] [--gif-width 1280]');
  process.exit(1);
}
const FPS = +opt('fps', 25);
const GIF_W = +opt('gif-width', 1280);
const LOOPS = +opt('loops', 1);
const STILL = opt('still', null);

function ffmpegPath() {
  try { const p = require('ffmpeg-static'); if (p && fs.existsSync(p)) return p; } catch (e) { /* 없으면 시스템 ffmpeg 를 쓴다 */ }
  const r = spawnSync('ffmpeg', ['-version']);
  if (r.status === 0) return 'ffmpeg';
  console.error('ffmpeg 를 찾지 못했다. engine 폴더에서 npm install 을 다시 하거나 ffmpeg 를 설치한다.');
  process.exit(1);
}

// 구글 폰트 대신 설치된 폰트 파일을 쓴다. 녹화 환경이 외부 망을 못 쓸 때도 글꼴이 같게 나온다
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

(async () => {
  let html = fs.readFileSync(htmlPath, 'utf8').replace(/<link[^>]+fonts\.(googleapis|gstatic)[^>]*>/g, '');
  html = html.replace('</style>', localFonts() + '</style>');
  const tmp = path.join(path.dirname(path.resolve(outBase)), `.rec-${process.pid}.html`);
  fs.mkdirSync(path.dirname(tmp), { recursive: true });
  fs.writeFileSync(tmp, html);

  const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.clock.install({ time: Date.now() });
  await page.goto('file://' + tmp);
  await page.evaluate(() => document.fonts.ready);
  await page.clock.pauseAt(Date.now() + 1000);
  // 시계를 멈춘 뒤 처음부터 다시 시작해야 첫 프레임이 0초가 되고 GIF 가 이음매 없이 돈다
  await page.evaluate(() => window.SLIDE && window.SLIDE.ready && window.SLIDE.restart());
  const state = await page.evaluate(() => ({ loop: window.SLIDE && window.SLIDE.loop, error: window.SLIDE && window.SLIDE.error }));
  if (state.error || errors.length) {
    console.error('장면을 그리지 못했다: ' + (state.error || errors.join('\n')));
    await browser.close(); fs.unlinkSync(tmp); process.exit(2);
  }

  if (STILL != null) {
    await page.clock.runFor(+STILL);
    const out = outBase.endsWith('.png') ? outBase : outBase + '.png';
    await page.screenshot({ path: out });
    console.log('만듦: ' + out);
    await browser.close(); fs.unlinkSync(tmp); return;
  }

  const ff = ffmpegPath();
  const total = Math.round((state.loop / 1000) * LOOPS * FPS);
  const mp4 = `${outBase}.rec-${process.pid}.mp4`;
  const enc = spawn(ff, ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-i', '-',
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '18', '-preset', 'medium', '-movflags', '+faststart', mp4],
  { stdio: ['pipe', 'ignore', 'inherit'] });
  for (let i = 0; i < total; i++) {
    enc.stdin.write(await page.screenshot({ type: 'jpeg', quality: 95 }));
    await page.clock.runFor(1000 / FPS);
    if (i % FPS === 0) process.stdout.write(`\r녹화 ${Math.round((i / total) * 100)}%`);
  }
  enc.stdin.end();
  await new Promise((r) => enc.on('close', r));
  await browser.close(); fs.unlinkSync(tmp);
  process.stdout.write('\r녹화 100%\n');

  {
    const gif = outBase + '.gif';
    const vf = `fps=${FPS},scale=${GIF_W}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle`;
    const r = spawnSync(ff, ['-y', '-loglevel', 'error', '-i', mp4, '-vf', vf, '-loop', '0', gif], { stdio: 'inherit' });
    if (r.status !== 0) process.exit(r.status);
    console.log(`만듦: ${gif} (${(fs.statSync(gif).size / 1048576).toFixed(1)}MB)`);
  }
  fs.unlinkSync(mp4);
})();
