#!/usr/bin/env node
/*
 * build.js 가 만든 장면 HTML 을 건넬 파일 하나로 다듬는다.
 * 사용법: node pack.js <scene.html> <out.html> [--offline]
 * 기본은 구글 폰트를 링크로 불러 파일이 작다. --offline 을 주면 글꼴을 파일 안에 넣어 인터넷 없이도 같게 보인다.
 */
const fs = require('fs');
const path = require('path');

const args = process.argv.slice(2);
const offline = args.includes('--offline');
const [inPath, outPath] = args.filter((a) => !a.startsWith('--'));
if (!inPath || !outPath) {
  console.error('사용법: node pack.js <scene.html> <out.html> [--offline]');
  process.exit(1);
}

let html = fs.readFileSync(inPath, 'utf8');
if (offline) {
  const base = path.join(__dirname, 'node_modules', '@fontsource');
  const face = (family, pkg, sub, w) => {
    const f = path.join(base, pkg, 'files', `${pkg}-${sub}-${w}-normal.woff2`);
    if (!fs.existsSync(f)) { console.error(`글꼴 파일이 없다: ${f}. engine 폴더에서 npm install 을 한다.`); process.exit(1); }
    return `@font-face{font-family:"${family}";font-weight:${w};src:url(data:font/woff2;base64,${fs.readFileSync(f).toString('base64')}) format("woff2")}`;
  };
  let css = '';
  for (const w of [400, 500, 600, 700]) for (const sub of ['korean', 'latin']) css += face('IBM Plex Sans KR', 'ibm-plex-sans-kr', sub, w);
  for (const w of [400, 500]) css += face('IBM Plex Mono', 'ibm-plex-mono', 'latin', w);
  html = html.replace(/<link[^>]+fonts\.(googleapis|gstatic)[^>]*>\n?/g, '').replace('</style>', css + '\n</style>');
}
fs.mkdirSync(path.dirname(path.resolve(outPath)), { recursive: true });
fs.writeFileSync(outPath, html);
console.log(`만듦: ${outPath} (${(fs.statSync(outPath).size / 1024 / 1024).toFixed(1)}MB${offline ? ', 글꼴 포함' : ''})`);
