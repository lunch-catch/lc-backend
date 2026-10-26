/*
 * 슬라이드 애니메이션 엔진이다. window.SLIDE_SPEC 의 장면 정의를 1600x900 무대에 그리고 timeline 을 반복 재생한다.
 * 시간은 requestAnimationFrame 으로만 흐르므로 녹화기가 시계를 멈추고 프레임 단위로 넘길 수 있다.
 */
(function () {
  var W = 1600, H = 900, NS = 'http://www.w3.org/2000/svg';
  var spec = window.SLIDE_SPEC;
  var SPEED = spec.speed || 0.2;
  var LOOP = spec.loop || 12000;

  var ICONS = {
    scan: '<path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3"/><rect x="8" y="8" width="8" height="8" rx="1.5"/>',
    chart: '<path d="M4 20h16M5 16l4-5 4 3 6-8"/><path d="M16 6h3v3"/>',
    upload: '<path d="M12 15V4M7.5 8.5 12 4l4.5 4.5M5 14v5h14v-5"/>',
    play: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M10 9v6l5-3z"/>',
    shield: '<path d="M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6z"/><path d="m9 12 2 2 4-4"/>',
    gauge: '<path d="M5 18a8 8 0 1 1 14 0"/><path d="m12 14 4-5"/>',
    db: '<ellipse cx="12" cy="6" rx="7" ry="3"/><path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"/>',
    server: '<rect x="4" y="4" width="16" height="7" rx="1.5"/><rect x="4" y="13" width="16" height="7" rx="1.5"/><path d="M8 7.5h.01M8 16.5h.01"/>',
    clock: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/>',
    bolt: '<path d="M13 3 5 14h6l-1 7 8-11h-6z"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6"/>',
    queue: '<rect x="3" y="9" width="4" height="6" rx="1"/><rect x="10" y="9" width="4" height="6" rx="1"/><rect x="17" y="9" width="4" height="6" rx="1"/>',
    coin: '<circle cx="12" cy="12" r="8"/><path d="M14.5 9.5c-.5-1-1.4-1.5-2.5-1.5-1.4 0-2.5.8-2.5 2s1 1.7 2.5 2 2.5.8 2.5 2-1.1 2-2.5 2c-1.1 0-2-.5-2.5-1.5M12 6.5v11"/>',
    bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 20a2 2 0 0 0 4 0"/>',
    cloud: '<path d="M7 18a4 4 0 0 1-.5-8A5.5 5.5 0 0 1 17 8.5 4.5 4.5 0 0 1 17.5 18z"/>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>'
  };

  var root = document.getElementById('stage');
  var wrap = document.getElementById('wrap');

  function fit() {
    var w = wrap.clientWidth, h = wrap.clientHeight, s = Math.min(w / W, h / H);
    root.style.transform = 'translate(' + (w - W * s) / 2 + 'px,' + (h - H * s) / 2 + 'px) scale(' + s + ')';
  }
  window.addEventListener('resize', fit);

  function el(tag, cls, parent, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    if (parent) parent.appendChild(e);
    return e;
  }
  function pos(e, o) {
    e.style.left = o.x + 'px'; e.style.top = o.y + 'px';
    if (o.w != null) e.style.width = o.w + 'px';
    if (o.h != null) e.style.height = o.h + 'px';
  }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]; }); }

  var reg = {};   // id -> { def, el, parts }
  var dotsLayer, wiresSvg;

  // 'box1.r' 같은 기준점이나 [x, y] 를 좌표로 바꾼다
  function anchor(a) {
    if (Array.isArray(a)) return [a[0], a[1]];
    var m = /^([^.]+)\.(c|l|r|t|b|tl|tr|bl|br)([+-]\d+)?([+-]\d+)?$/.exec(a);
    if (!m || !reg[m[1]]) throw new Error('알 수 없는 기준점: ' + a);
    var d = reg[m[1]].def, x = d.x, y = d.y, w = d.w || 0, h = d.h || 0, p = m[2];
    var pt = {
      c: [x + w / 2, y + h / 2], l: [x, y + h / 2], r: [x + w, y + h / 2], t: [x + w / 2, y], b: [x + w / 2, y + h],
      tl: [x, y], tr: [x + w, y], bl: [x, y + h], br: [x + w, y + h]
    }[p];
    var dx = m[3] ? +m[3] : 0, dy = m[4] ? +m[4] : 0;
    if (m[3] && !m[4] && (p === 't' || p === 'b')) { dx = +m[3]; }
    if (m[3] && !m[4] && (p === 'l' || p === 'r')) { dy = +m[3]; dx = 0; }
    return [pt[0] + dx, pt[1] + dy];
  }

  // 축이 어긋난 두 점 사이에 꺾는 점을 넣는다. hvh 는 가로, 세로, 가로 순이다
  function route(points, elbow) {
    elbow = elbow || 'hvh';
    var out = [points[0]];
    for (var i = 1; i < points.length; i++) {
      var a = out[out.length - 1], b = points[i];
      if (a[0] !== b[0] && a[1] !== b[1] && elbow !== 'none') {
        if (elbow === 'hv') out.push([b[0], a[1]]);
        else if (elbow === 'vh') out.push([a[0], b[1]]);
        else if (elbow === 'vhv') { var my = Math.round((a[1] + b[1]) / 2); out.push([a[0], my], [b[0], my]); }
        else { var mx = Math.round((a[0] + b[0]) / 2); out.push([mx, a[1]], [mx, b[1]]); }
      }
      out.push(b);
    }
    return out;
  }

  function wirePoints(w) { return route(w.points.map(anchor), w.elbow); }

  function render() {
    reg = {};
    root.innerHTML = '';
    var bg = el('div', 'bg', root);
    if (spec.title) pos(el('div', 'headline', root, esc(spec.title)), { x: 100, y: 78 });
    if (spec.logo) pos(el('div', 'logo', root, esc(spec.logo)), { x: 1380, y: 92, w: 140 });

    var layers = { panel: el('div', 'layer', root), under: el('div', 'layer', root) };
    wiresSvg = document.createElementNS(NS, 'svg');
    wiresSvg.setAttribute('class', 'wires'); wiresSvg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
    root.appendChild(wiresSvg);
    var defs = document.createElementNS(NS, 'defs');
    defs.innerHTML = '<marker id="arr" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M1 1.5 6.5 4 1 6.5" fill="none" stroke="currentColor" stroke-width="1.3"/></marker>';
    wiresSvg.appendChild(defs);
    dotsLayer = document.createElementNS(NS, 'g');
    var top = el('div', 'layer top', root);

    (spec.elements || []).forEach(function (d) {
      var parent = d.type === 'panel' ? layers.panel : top;
      var made = build(d, parent);
      if (d.id) reg[d.id] = { def: d, el: made.el, parts: made.parts || {} };
    });

    (spec.wires || []).forEach(function (w) {
      var pts = wirePoints(w);
      var pl = document.createElementNS(NS, 'polyline');
      pl.setAttribute('points', pts.map(function (p) { return p.join(','); }).join(' '));
      pl.setAttribute('class', 'wire' + (w.dash ? ' dash' : '') + (w.tone ? ' t-' + w.tone : ''));
      if (w.arrow) pl.setAttribute('marker-end', 'url(#arr)');
      wiresSvg.appendChild(pl);
      if (w.id) reg[w.id] = { def: w, el: pl, pts: pts };
      if (w.label) {
        var t = document.createElementNS(NS, 'text');
        var lp = w.label.at ? anchor(w.label.at) : mid(pts);
        t.setAttribute('x', lp[0] + (w.label.dx || 0)); t.setAttribute('y', lp[1] + (w.label.dy == null ? -8 : w.label.dy));
        t.setAttribute('text-anchor', w.label.align || 'middle');
        t.textContent = w.label.text; wiresSvg.appendChild(t);
      }
    });
    wiresSvg.appendChild(dotsLayer);
  }

  function mid(pts) {
    var segs = [], total = 0;
    for (var i = 1; i < pts.length; i++) { var d = dist(pts[i - 1], pts[i]); segs.push(d); total += d; }
    var half = total / 2;
    for (var k = 0; k < segs.length; k++) {
      if (half <= segs[k]) { var f = segs[k] ? half / segs[k] : 0; return [pts[k][0] + (pts[k + 1][0] - pts[k][0]) * f, pts[k][1] + (pts[k + 1][1] - pts[k][1]) * f]; }
      half -= segs[k];
    }
    return pts[0];
  }
  function dist(a, b) { return Math.hypot(b[0] - a[0], b[1] - a[1]); }

  function build(d, parent) {
    var e, parts = {};
    switch (d.type) {
      case 'panel':
        e = el('div', 'panel' + (d.dashed ? ' dashed' : '') + (d.tone ? ' t-' + d.tone : ''), parent);
        if (d.title) parts.title = el('div', 'p-title', e, esc(d.title));
        if (d.sub) parts.sub = el('div', 'p-sub', e, esc(d.sub));
        break;
      case 'box':
        e = el('div', 'box s-' + (d.size || 'm') + (d.align === 'center' ? ' center' : '') + (d.tone ? ' t-' + d.tone : ''), parent);
        var head = (d.icon || d.num) ? el('div', 'b-head', e) : null;
        if (d.icon && ICONS[d.icon]) el('div', 'b-icon', head, '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">' + ICONS[d.icon] + '</svg>');
        if (d.num) el('div', 'b-num', head, esc(d.num));
        parts.title = el('div', 'b-title', e, esc(d.title));
        if (d.sub != null) parts.sub = el('div', 'b-sub', e, esc(d.sub));
        if (d.value != null) parts.value = el('div', 'b-value', e, esc(d.value));
        if (d.progress != null) {
          var tr = el('div', 'b-track', e); parts.fill = el('div', 'b-fill', tr);
          parts.fill.style.width = (d.progress * 100) + '%';
        }
        break;
      case 'label':
        e = el('div', 'label c-' + (d.color || 'dim') + ' z-' + (d.size || 's') + (d.mono ? ' mono' : ''), parent, esc(d.text));
        if (d.align) e.style.textAlign = d.align;
        break;
      case 'formula':
        e = el('div', 'formula z-' + (d.size || 'l'), parent, d.html);
        break;
      case 'chips':
        e = el('div', 'chips', parent);
        parts.items = d.items.map(function (t, i) {
          if (i > 0 && d.joined !== false) el('span', 'chip-join', e);
          return el('span', 'chip', e, esc(t));
        });
        break;
      case 'cells':
        e = el('div', 'cells', parent);
        e.style.gridTemplateColumns = 'repeat(' + (d.cols || d.n) + ',' + (d.size || 18) + 'px)';
        e.style.gap = (d.gap == null ? 6 : d.gap) + 'px';
        parts.items = [];
        for (var i = 0; i < d.n; i++) {
          var c = el('span', 'cell', e); c.style.height = (d.size || 18) + 'px';
          parts.items.push(c);
        }
        paintCells(parts.items, d.fill || []);
        break;
      case 'rows':
        e = el('div', 'rows', parent);
        parts.items = d.items.map(function (r) {
          var row = el('div', 'row' + (r.tone ? ' t-' + r.tone : ''), e);
          row.style.height = (d.rowH || 34) + 'px';
          var p = { el: row };
          p.label = el('span', 'r-label', row, esc(r.label));
          if (r.bar != null) { var tk = el('span', 'r-track', row); p.fill = el('span', 'r-fill', tk); p.fill.style.width = r.bar * 100 + '%'; }
          if (r.meta != null) p.meta = el('span', 'r-meta', row, esc(r.meta));
          return p;
        });
        e.style.gap = (d.gap == null ? 8 : d.gap) + 'px';
        break;
      default:
        throw new Error('알 수 없는 요소 type: ' + d.type);
    }
    pos(e, d);
    return { el: e, parts: parts };
  }

  function paintCells(items, fill) {
    items.forEach(function (c, i) {
      var k = fill[i];
      c.className = 'cell' + (k ? ' on k-' + k : '');
    });
  }

  /* ---------- 재생 ---------- */

  var sim = 0, timers = [], movers = [], paused = false;
  function after(ms, fn) { timers.push({ t: sim + ms, fn: fn }); }

  function dotPath(p) {
    var raw = [];
    p.forEach(function (it) {
      if (it && it.wire) {
        var w = reg[it.wire]; if (!w) throw new Error('알 수 없는 wire: ' + it.wire);
        var pts = w.pts.slice(); if (it.rev) pts.reverse();
        raw.push({ fixed: pts });
      } else raw.push({ pt: anchor(it) });
    });
    var out = [];
    raw.forEach(function (r) {
      if (r.fixed) { r.fixed.forEach(function (q) { pushPt(out, q, 'none'); }); }
      else pushPt(out, r.pt, 'hvh');
    });
    return out;
  }
  function pushPt(out, q, elbow) {
    if (!out.length) { out.push(q); return; }
    var a = out[out.length - 1];
    if (a[0] === q[0] && a[1] === q[1]) return;
    var r = route([a, q], elbow); for (var i = 1; i < r.length; i++) out.push(r[i]);
  }

  // 단계 하나를 시작하고 끝나면 done 을 부른다. async 단계는 바로 done 한다
  function exec(s, done) {
    var fin = s.async ? function () {} : done;
    if (s.async) done();
    if (s.wait != null && Object.keys(s).length <= 2) { after(s.wait, fin); return; }
    if (s.par) { var left = s.par.length; if (!left) return fin(); s.par.forEach(function (x) { exec(x, function () { if (--left === 0) fin(); }); }); return; }
    if (s.seq) { runSeq(s.seq, fin); return; }
    if (s.dot) {
      var pts = dotPath(s.dot), c = document.createElementNS(NS, 'circle');
      c.setAttribute('r', s.r || 4.5); c.setAttribute('class', 'dot k-' + (s.color || 'fg'));
      dotsLayer.appendChild(c);
      var segs = [], total = 0;
      for (var i = 1; i < pts.length; i++) { var d = dist(pts[i - 1], pts[i]); segs.push(d); total += d; }
      var m = { el: c, pts: pts, segs: segs, total: total, d: 0, keep: s.keep, done: fin, speed: s.speed || SPEED };
      place(m); movers.push(m); return;
    }
    if (s.hl) { var t = reg[s.hl].el; t.classList.add('hit'); after(s.ms || 600, function () { t.classList.remove('hit'); }); after(s.hold ? s.ms || 600 : 0, fin); return; }
    if (s.active) { reg[s.active].el.classList.toggle('active', s.on !== false); return fin(); }
    if (s.progress) {
      var f = reg[s.progress].parts.fill, from = parseFloat(f.style.width) / 100, to = s.to, dur = s.ms || 800;
      movers.push({ tween: true, t0: sim, dur: dur, apply: function (k) { f.style.width = (from + (to - from) * k) * 100 + '%'; }, done: fin });
      return;
    }
    if (s.text) {
      var r = reg[s.text], part = r.parts[s.field || 'value'] || r.el;
      part.textContent = s.value; return fin();
    }
    if (s.cells) {
      var cr = reg[s.cells], cur = cr.parts.items.map(function (c) { var m2 = /k-(\w+)/.exec(c.className); return m2 ? m2[1] : null; });
      if (s.set) cur = s.set.slice();
      if (s.push) { var idx = cur.indexOf(null); if (idx < 0) idx = cur.length - 1; cur[idx] = s.push; }
      if (s.pop) { cur.shift(); cur.push(null); }
      if (s.paint != null) cur[s.paint] = s.color || null;
      while (cur.length < cr.parts.items.length) cur.push(null);
      paintCells(cr.parts.items, cur); return fin();
    }
    if (s.chip) {
      reg[s.chip].parts.items.forEach(function (c, i) { c.classList.toggle('on', i === s.index); });
      return fin();
    }
    if (s.row) {
      var rr = reg[s.row].parts.items;
      if (s.on != null && rr[s.index]) rr[s.index].el.classList.toggle('on', !!s.on);
      else if (s.index != null && s.focus !== false) rr.forEach(function (p, i) { p.el.classList.toggle('on', i === s.index); });
      var p = rr[s.index];
      if (p && s.bar != null && p.fill) p.fill.style.width = s.bar * 100 + '%';
      if (p && s.meta != null && p.meta) p.meta.textContent = s.meta;
      if (p && s.label != null) p.label.textContent = s.label;
      if (p && s.tone !== undefined) { p.el.className = p.el.className.replace(/ t-\w+/g, '') + (s.tone ? ' t-' + s.tone : ''); }
      if (s.index === -1) rr.forEach(function (q) { q.el.classList.remove('on'); });
      return fin();
    }
    throw new Error('알 수 없는 단계: ' + JSON.stringify(s));
  }
  function runSeq(steps, done) {
    var i = 0;
    (function next() { if (i >= steps.length) return done && done(); exec(steps[i++], next); })();
  }

  function place(m) {
    var d = Math.min(m.d, m.total), k = 0;
    while (k < m.segs.length - 1 && d > m.segs[k]) { d -= m.segs[k]; k++; }
    var a = m.pts[k], b = m.pts[k + 1] || a, f = m.segs[k] ? Math.min(d / m.segs[k], 1) : 1;
    m.el.setAttribute('cx', a[0] + (b[0] - a[0]) * f); m.el.setAttribute('cy', a[1] + (b[1] - a[1]) * f);
  }

  var loopStart = 0;
  function start() {
    render(); timers = []; movers = []; loopStart = sim;
    (spec.timeline || []).forEach(function (track) {
      after(track.at || 0, function () {
        if (track.every) {
          (function rep() { runSeq(track.steps); if (sim + track.every < loopStart + LOOP) after(track.every, rep); })();
        } else runSeq(track.steps);
      });
    });
  }

  var last = null;
  function frame(now) {
    if (last == null) last = now;
    var dt = Math.min(now - last, 100); last = now;
    if (!paused) {
      sim += dt;
      if (sim - loopStart >= LOOP) { sim = loopStart + LOOP; start(); }
      var guard = 0;
      timers.sort(function (a, b) { return a.t - b.t; });
      while (timers.length && timers[0].t <= sim && guard++ < 500) { timers.shift().fn(); timers.sort(function (a, b) { return a.t - b.t; }); }
      for (var i = movers.length - 1; i >= 0; i--) {
        var m = movers[i];
        if (m.tween) { var k = Math.min((sim - m.t0) / m.dur, 1); m.apply(1 - Math.pow(1 - k, 2)); if (k >= 1) { movers.splice(i, 1); m.done(); } continue; }
        m.d += dt * m.speed; place(m);
        if (m.d >= m.total) { movers.splice(i, 1); if (!m.keep) m.el.remove(); m.done(); }
      }
    }
    requestAnimationFrame(frame);
  }

  function toggle() { paused = !paused; }
  wrap.addEventListener('click', toggle);
  document.addEventListener('keydown', function (e) {
    if (e.code === 'Space') { e.preventDefault(); toggle(); }
    if (e.key === 'f' || e.key === 'F') {
      if (document.fullscreenElement) document.exitFullscreen();
      else if (document.documentElement.requestFullscreen) document.documentElement.requestFullscreen().catch(function () {});
    }
  });
  document.addEventListener('fullscreenchange', function () { setTimeout(fit, 50); });

  window.SLIDE = { loop: LOOP, ready: false, restart: function () { sim = 0; last = null; paused = false; start(); } };
  fit();
  try { start(); window.SLIDE.ready = true; } catch (err) {
    window.SLIDE.error = String(err && err.message || err);
    root.innerHTML = '<div class="err">' + esc(window.SLIDE.error) + '</div>';
  }
  requestAnimationFrame(frame);
})();
