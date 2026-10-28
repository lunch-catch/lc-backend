#!/usr/bin/env node
/*
 * 참고 영상에서 슬라이드가 바뀌는 지점을 찾고 슬라이드마다 볼 그림을 뽑는다.
 * 에이전트는 뽑은 그림을 보고 reference/patterns.md 에 새 패턴을 더한다. 영상과 그림은 저장소에 올리지 않는다.
 *
 * 사용법: node learn/extract.js <영상.mp4> <출력 폴더> [--crop x,y,w,h] [--min 3]
 *   --crop   슬라이드가 보이는 영역이다. 영상 크기에 대한 비율(0~1)로 준다. 기본은 0,0.05,0.66,0.77 이고
 *            오른쪽 위 발표자 화면과 아래 자막을 피한다. 슬라이드만 나오는 영상이면 0,0,1,1 을 준다
 *   --min    이 초보다 짧은 구간은 버린다. 기본 3초
 * 결과:
 *   <출력>/sheets/NN.png   4초 간격 장면 20장을 한 장에 모은 전체 훑어보기
 *   <출력>/slides/NN.png   슬라이드마다 0.8초 간격 4장. 무엇이 움직이는지 본다
 *   <출력>/index.md        슬라이드마다 구간과 그림 경로
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const args = process.argv.slice(2);
const [video, outDir] = args;
const opt = (n, d) => { const i = args.indexOf('--' + n); return i < 0 ? d : args[i + 1]; };
if (!video || !outDir) { console.error('사용법: node learn/extract.js <영상.mp4> <출력 폴더> [--crop x,y,w,h] [--min 3]'); process.exit(1); }
const crop = opt('crop', '0,0.05,0.66,0.77').split(',').map(Number);
const MIN = +opt('min', 3);

function ffmpegPath() {
  try { const p = require(path.join(__dirname, '..', 'engine', 'node_modules', 'ffmpeg-static')); if (p && fs.existsSync(p)) return p; } catch (e) { /* 시스템 ffmpeg 를 쓴다 */ }
  return 'ffmpeg';
}
const FF = ffmpegPath();
const run = (a, opts) => { const r = spawnSync(FF, ['-hide_banner', '-loglevel', 'error', ...a], { maxBuffer: 1 << 30, ...opts }); if (r.status !== 0) { console.error(String(r.stderr)); process.exit(1); } return r; };

fs.mkdirSync(path.join(outDir, 'sheets'), { recursive: true });
fs.mkdirSync(path.join(outDir, 'slides'), { recursive: true });

// 1초에 한 장을 96x54 흑백으로 받아 앞 장과의 차이를 잰다
const TW = 96, TH = 54;
const cf = `crop=iw*${crop[2]}:ih*${crop[3]}:iw*${crop[0]}:ih*${crop[1]},scale=${TW}:${TH},format=gray`;
const raw = run(['-i', video, '-vf', `fps=1,${cf}`, '-f', 'rawvideo', '-']).stdout;
const n = Math.floor(raw.length / (TW * TH));
const diff = [0];
for (let i = 1; i < n; i++) {
  let s = 0;
  for (let k = 0; k < TW * TH; k++) s += Math.abs(raw[i * TW * TH + k] - raw[(i - 1) * TW * TH + k]);
  diff.push(s / (TW * TH));
}
const segs = [];
let st = 0;
for (let i = 1; i < n; i++) if (diff[i] > 9) { segs.push([st, i - 1]); st = i; }
segs.push([st, n - 1]);
const slides = segs.filter(([a, b]) => b - a + 1 >= MIN);

// 전체 훑어보기
run(['-i', video, '-vf', 'fps=1/4,scale=384:216,tile=5x4:padding=4', path.join(outDir, 'sheets', '%02d.png')]);

// 슬라이드마다 0.8초 간격 네 장
const lines = ['# 참고 영상 슬라이드', '', `영상: ${path.basename(video)}, 길이 ${n}초, 슬라이드 ${slides.length}개`, '', '| 번호 | 구간 | 그림 |', '|---|---|---|'];
slides.forEach(([a, b], i) => {
  const t = Math.min(a + 1, b);
  const f = path.join(outDir, 'slides', `${String(i + 1).padStart(2, '0')}.png`);
  run(['-ss', String(t), '-i', video, '-vf', 'fps=1.25,scale=640:-1,tile=2x2:padding=4', '-frames:v', '1', f]);
  const mmss = (s) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  lines.push(`| ${i + 1} | ${mmss(a)} ~ ${mmss(b)} | slides/${path.basename(f)} |`);
});
fs.writeFileSync(path.join(outDir, 'index.md'), lines.join('\n') + '\n');
console.log(`슬라이드 ${slides.length}개를 뽑았다: ${path.join(outDir, 'index.md')}`);
